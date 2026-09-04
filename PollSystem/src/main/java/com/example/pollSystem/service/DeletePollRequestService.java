package com.example.pollSystem.service;

import com.example.pollSystem.entity.DeletePollRequest;
import com.example.pollSystem.entity.DeletePollRequestStatus;
import com.example.pollSystem.exception.DeletePollRequestExpiredException;
import com.example.pollSystem.exception.DeletePollRequestNotFoundException;
import com.example.pollSystem.exception.DeletePollRequestNotPendingException;
import com.example.pollSystem.exception.PollOwnershipException;
import com.example.pollSystem.repository.DeletePollRequestRepository;
import com.example.pollSystem.utils.SecurityUtils;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Gestisce il ciclo di vita delle richieste di eliminazione poll in attesa di conferma
 * out-of-band (URL-mode elicitation), come richiesto dal tool MCP {@code delete_poll}.
 * <p>
 * La conferma vera e propria (e quindi l'esecuzione di {@code PollService.deletePoll})
 * avviene esclusivamente tramite l'endpoint REST dedicato, protetto da uno scope OAuth2
 * distinto da quello del canale MCP: questa classe si limita a creare e tracciare la
 * richiesta pending, non a eseguire l'eliminazione.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeletePollRequestService {

    private final DeletePollRequestRepository deletePollRequestRepository;
    private final SecurityUtils securityUtils;
    private final PollService pollService;

    // ObjectProvider (lazy) e non McpSyncServer diretto: quest'ultimo viene costruito
    // dall'auto-configurazione MCP raccogliendo i metodi @McpTool/@McpPrompt/@McpResource di
    // TUTTI i bean (incluso PollMcpTools) al momento della sua creazione. Se questo service
    // (usato da PollMcpTools) dipendesse direttamente da McpSyncServer, Spring dovrebbe
    // costruirlo PRIMA di PollMcpTools stesso, ottenendo un server MCP con zero tool/prompt/
    // resource registrati (bug osservato: "No tool methods found in the provided tool objects: []").
    // Con ObjectProvider il bean viene risolto solo al primo utilizzo effettivo (a runtime, a
    // contesto ormai completamente avviato).
    private final ObjectProvider<McpSyncServer> mcpSyncServerProvider;

    @Value("${poll.delete-confirmation.ttl-minutes:5}")
    private long ttlMinutes;

    @Value("${pollsystem.public-base-url}")
    private String publicBaseUrl;

    /**
     * Crea e persiste una nuova richiesta di eliminazione pending per il poll indicato,
     * associata all'utente correntemente autenticato (il richiedente dell'operazione MCP).
     * L'id generato (UUID) viene poi usato come {@code elicitationId} nell'URL elicitation
     * e come chiave per correlare la successiva conferma REST.
     *
     * @param mcpSessionId id della sessione MCP (Streamable HTTP) da cui è stato invocato
     *                     {@code request_delete_poll}, usato in seguito per inviare la notifica
     *                     out-of-band {@code elicitation/complete} (best-effort: vedi
     *                     {@link #notifyElicitationComplete(DeletePollRequest, String)}).
     */
    @Transactional
    public DeletePollRequest createPendingRequest(Long pollId, String mcpSessionId) {
        LocalDateTime now = LocalDateTime.now();

        DeletePollRequest request = DeletePollRequest.builder()
                .id(UUID.randomUUID().toString())
                .pollId(pollId)
                .requestedByUserId(securityUtils.currentUserId())
                .mcpSessionId(mcpSessionId)
                .createdAt(now)
                .expiresAt(now.plusMinutes(ttlMinutes))
                .status(DeletePollRequestStatus.PENDING)
                .build();

        DeletePollRequest saved = deletePollRequestRepository.save(request);

        log.info(
                "Delete confirmation request {} created for poll {} by user {} (expires at {})",
                saved.getId(),
                pollId,
                securityUtils.currentUsername(),
                saved.getExpiresAt()
        );

        return saved;
    }

    /**
     * Costruisce l'URL della pagina statica di conferma (servita da PollSystem3),
     * includendo l'{@code elicitationId} necessario a recuperare la richiesta pending.
     */
    public String buildConfirmationUrl(String elicitationId) {
        return UriComponentsBuilder.fromUriString(publicBaseUrl)
                .path("/confirm-delete.html")
                .queryParam("elicitationId", elicitationId)
                .toUriString();
    }

    /**
     * Restituisce la richiesta di eliminazione più recente (qualunque stato) per il poll indicato e
     * l'utente correntemente autenticato. Usata dal tool MCP {@code request_delete_poll} per rilevare
     * un eventuale retry della stessa tool/call (es. dopo un errore JSON-RPC
     * {@code URL_ELICITATION_REQUIRED}) PRIMA di ricontrollare l'esistenza/eliminabilità del poll:
     * se la conferma precedente è già stata CONFIRMED, il poll non esiste più nel database e un
     * controllo preventivo (es. {@code PollService#checkCanDeletePoll}) fallirebbe con "Poll not
     * found", un esito fuorviante rispetto al reale stato (successo) dell'operazione. Interrogare
     * prima questo storico permette al tool di restituire l'esito corretto direttamente, dato che la
     * notifica out-of-band {@code elicitation/complete} da sola non può veicolare informazioni
     * specifiche al chiamante.
     * <p>
     * Se la richiesta più recente è PENDING ma il TTL è nel frattempo scaduto, viene marcata EXPIRED
     * (stesso comportamento di {@link #getStatus(String)}) prima di essere restituita, così il
     * chiamante vede sempre lo stato aggiornato e può decidere di procedere con una nuova richiesta.
     */
    @Transactional
    public Optional<DeletePollRequest> findLatestRequestForCurrentUser(Long pollId) {
        Long currentUserId = securityUtils.currentUserId();

        Optional<DeletePollRequest> latest = deletePollRequestRepository
                .findFirstByPollIdAndRequestedByUserIdOrderByCreatedAtDesc(pollId, currentUserId);

        latest.ifPresent(request -> {
            if (request.getStatus() == DeletePollRequestStatus.PENDING
                    && request.getExpiresAt().isBefore(LocalDateTime.now())) {
                request.setStatus(DeletePollRequestStatus.EXPIRED);
                deletePollRequestRepository.save(request);
                notifyElicitationComplete(request, "EXPIRED");
            }
        });

        return latest;
    }

    /**
     * Verifica e consuma una richiesta di eliminazione pending, quindi esegue la cancellazione
     * effettiva del poll. Chiamato esclusivamente dall'endpoint REST di conferma, protetto da
     * uno scope OAuth2 dedicato ("rest") distinto da quello del client MCP.
     * <p>
     * Controlli eseguiti, in ordine:
     * <ol>
     *     <li>la richiesta con {@code elicitationId} esiste;</li>
     *     <li>è ancora in stato PENDING (non già confermata/annullata: non riutilizzabile);</li>
     *     <li>non è scaduta (TTL breve, {@code poll.delete-confirmation.ttl-minutes});</li>
     *     <li>l'utente autenticato che conferma è esattamente lo stesso che ha richiesto l'eliminazione
     *     (nessuna deroga, nemmeno per ADMIN): la conferma è un atto personale dell'utente che ha
     *     avviato l'operazione dal canale MCP.</li>
     * </ol>
     * La richiesta viene marcata CONFIRMED prima di procedere, così da non essere più riutilizzabile
     * anche in caso di doppio click/retry concorrenti. L'eliminazione vera e propria è delegata a
     * {@link PollService#deletePoll(Long)}, che ricontrolla autonomamente ownership/ruolo.
     */
    @Transactional
    public void confirmAndDelete(String elicitationId) {
        DeletePollRequest request = deletePollRequestRepository.findById(elicitationId)
                .orElseThrow(() -> new DeletePollRequestNotFoundException(
                        "Delete confirmation request not found"));

        if (request.getStatus() != DeletePollRequestStatus.PENDING) {
            throw new DeletePollRequestNotPendingException(
                    "This deletion request has already been processed and cannot be reused");
        }

        if (request.getExpiresAt().isBefore(LocalDateTime.now())) {
            request.setStatus(DeletePollRequestStatus.EXPIRED);
            deletePollRequestRepository.save(request);
            notifyElicitationComplete(request, "EXPIRED");
            throw new DeletePollRequestExpiredException("This deletion request has expired");
        }

        Long currentUserId = securityUtils.currentUserId();
        if (!currentUserId.equals(request.getRequestedByUserId())) {
            throw new PollOwnershipException(
                    "Only the user who requested the deletion can confirm it");
        }

        // Consumo immediato della richiesta (non riutilizzabile), prima di eseguire l'operazione.
        request.setStatus(DeletePollRequestStatus.CONFIRMED);
        deletePollRequestRepository.save(request);

        // Il service ricontrolla autonomamente l'autorizzazione prima della cancellazione effettiva.
        pollService.deletePoll(request.getPollId());

        log.info(
                "Delete confirmation {} for poll {} confirmed by user {}",
                elicitationId,
                request.getPollId(),
                securityUtils.currentUsername()
        );

        notifyElicitationComplete(request, "CONFIRMED");
    }

    /**
     * Restituisce lo stato corrente di una richiesta di eliminazione pending, così che il client
     * MCP (che ha invocato {@code delete_poll} in modo non bloccante) possa successivamente
     * verificare, tramite il tool {@code get_delete_poll_status}, se l'utente ha confermato
     * l'operazione nel browser, se è ancora in attesa oppure se è scaduta.
     * <p>
     * Riservato all'utente che ha originariamente richiesto l'eliminazione (stesso controllo di
     * {@link #confirmAndDelete(String)}, nessuna deroga per ADMIN): l'elicitationId non deve poter
     * essere usato per curiosare sullo stato di richieste altrui.
     */
    @Transactional
    public DeletePollRequestStatus getStatus(String elicitationId) {
        DeletePollRequest request = deletePollRequestRepository.findById(elicitationId)
                .orElseThrow(() -> new DeletePollRequestNotFoundException(
                        "Delete confirmation request not found"));

        Long currentUserId = securityUtils.currentUserId();
        if (!currentUserId.equals(request.getRequestedByUserId())) {
            throw new PollOwnershipException(
                    "Only the user who requested the deletion can check its status");
        }

        // Se la richiesta è ancora PENDING ma il TTL è già scaduto, aggiorna lo stato persistito
        // (stesso comportamento applicato in confirmAndDelete) così lo stato riportato sia coerente.
        if (request.getStatus() == DeletePollRequestStatus.PENDING
                && request.getExpiresAt().isBefore(LocalDateTime.now())) {
            request.setStatus(DeletePollRequestStatus.EXPIRED);
            deletePollRequestRepository.save(request);
            notifyElicitationComplete(request, "EXPIRED");
        }

        return request.getStatus();
    }

    /**
     * Invia, in modo best-effort, la notifica out-of-band {@code elicitation/complete} sulla
     * sessione MCP (Streamable HTTP) da cui era stata originariamente invocata
     * {@code request_delete_poll}, così da evitare al client il polling su
     * {@code get_delete_poll_status}.
     * <p>
     * Non è mai una fonte di verità: se la sessione non è più valida (client disconnesso,
     * server riavviato, o — in un deployment multi-istanza senza sticky session/registro
     * sessioni condiviso — sessione gestita da un'altra istanza), l'invio fallisce e viene
     * semplicemente loggato, senza propagare l'errore al chiamante (che ha già completato,
     * con successo, l'operazione di business che l'ha originato).
     */
    private void notifyElicitationComplete(DeletePollRequest request, String status) {
        String sessionId = request.getMcpSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        McpSyncServer mcpSyncServer = mcpSyncServerProvider.getIfAvailable();
        if (mcpSyncServer == null) {
            return;
        }

        try {
            mcpSyncServer.sendElicitationComplete(
                    sessionId,
                    new McpSchema.ElicitationCompleteNotification(request.getId(), Map.of("status", status))
            );
            // sendElicitationComplete e' bloccante (Mono#block): se arriviamo qui senza eccezione,
            // il messaggio e' stato consegnato al transport per la sessione indicata (non e' pero'
            // garanzia che il client lo abbia elaborato/mostrato: dipende dal suo supporto a
            // notifications/elicitation/complete, che e' una feature draft del protocollo).
            log.info(
                    "elicitation/complete notification sent for request {} (status {}) on MCP session {}",
                    request.getId(), status, sessionId
            );
        } catch (Exception e) {
            log.warn(
                    "Could not send elicitation/complete notification for request {} (status {}) " +
                            "on MCP session {}: {}. The client can still poll get_delete_poll_status.",
                    request.getId(), status, sessionId, e.getMessage()
            );
        }
    }
}
