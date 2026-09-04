package com.example.pollSystem.service;

import com.example.pollSystem.dto.request.CreatePollRequestDto;
import com.example.pollSystem.dto.response.PollDetailsResponseDto;
import com.example.pollSystem.dto.response.PollListPageResponseDto;
import com.example.pollSystem.dto.response.PollResponseDto;
import com.example.pollSystem.entity.Option;
import com.example.pollSystem.entity.Poll;
import com.example.pollSystem.entity.PollStatus;
import com.example.pollSystem.entity.User;
import com.example.pollSystem.exception.PollNotFoundException;
import com.example.pollSystem.exception.PollNotModifiableException;
import com.example.pollSystem.exception.PollOwnershipException;
import com.example.pollSystem.exception.UsernameNotFoundException;
import com.example.pollSystem.mapper.PollMapper;
import com.example.pollSystem.repository.OptionRepository;
import com.example.pollSystem.repository.PollRepository;
import com.example.pollSystem.repository.UserRepository;
import com.example.pollSystem.repository.VoteRepository;
import com.example.pollSystem.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;


@Slf4j
@Service
@RequiredArgsConstructor
public class PollService {

    private final PollRepository pollRepository;
    private final UserRepository userRepository;
    private final OptionRepository optionRepository;
    private final VoteRepository voteRepository;
    private final PollMapper pollMapper;
    private final PollWinnerService pollWinnerService;
    private final SecurityUtils securityUtils;

    /**
     * Metodo di supporto utilizzato da @PreAuthorize per verificare la proprietà del poll.
     */
    @Transactional(readOnly = true)
    public boolean isOwner(Long pollId, Long userId) {
        if (pollId == null || userId == null) {
            return false;
        }
        return pollRepository.existsByIdAndOwnerId(pollId, userId);
    }

    /**
     * Controllo di scadenza "lazy", indipendente dal batch schedulato ({@code PollBatchJob}).
     * Il batch marca EXPIRED solo i poll la cui {@code expiresAt} cade nel giorno in cui gira
     * (una volta al giorno, a mezzanotte): se il batch è disabilitato, in ritardo, o comunque
     * non ancora passato per quel poll, un poll con {@code expiresAt} già nel passato potrebbe
     * restare ACTIVE nel DB a tempo indefinito, permettendo operazioni (voto, aggiunta/modifica/
     * cancellazione opzioni, chiusura) su un poll di fatto scaduto.
     * <p>
     * Questo metodo va chiamato da qualunque servizio che legga un {@link Poll} prima di applicare
     * regole basate sul suo status: se il poll è ancora ACTIVE ma la sua {@code expiresAt} è già
     * passata, viene marcato EXPIRED subito (calcolando il vincitore come farebbe il batch) e la
     * versione aggiornata/persistita viene restituita, così le regole successive vedono sempre lo
     * stato reale del poll, non quello potenzialmente stantio.
     */
    @Transactional
    public Poll ensureFreshStatus(Poll poll) {
        if (poll.getStatus() == PollStatus.ACTIVE && poll.getExpiresAt().isBefore(LocalDateTime.now())) {
            log.info(
                    "Poll {} is past its expiresAt ({}) but still ACTIVE in the DB: lazily expiring it now",
                    poll.getId(),
                    poll.getExpiresAt()
            );

            pollWinnerService.processPoll(poll);
            return pollRepository.save(poll);
        }

        return poll;
    }

    @Transactional // atomicità
    public PollResponseDto createPoll(CreatePollRequestDto request) {
        if (request.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Expiration date must be in the future");
        }

        // recupero l'id dell'utente autenticato dal claim JWT (nessuna query aggiuntiva)
        Long ownerId = securityUtils.currentUserId();

        Poll poll = pollMapper.toEntity(request);

        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        poll.setOwner(owner);
        poll.setStatus(PollStatus.ACTIVE);

        Poll saved = pollRepository.save(poll);

        log.info("Poll '{}' created by user {}", saved.getQuestion(), securityUtils.currentUsername());

        return pollMapper.toResponseDto(saved);
    }


    // Limite massimo imposto lato server sulla dimensione di una pagina, indipendentemente
    // da cosa richieda il chiamante (REST o MCP), per evitare query eccessivamente pesanti.
    private static final int MAX_PAGE_SIZE = 50;

    private void validatePaging(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size <= 0 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    public PollListPageResponseDto getPolls(String search, int page, int size) {
        return getPolls(search, page, size, null);
    }

    /**
     * @param statusFilter se {@code null}, nessun filtro di stato viene applicato (equivalente ad "ALL").
     *                     Usato dal tool MCP {@code search_polls} dopo che l'utente ha scelto, tramite
     *                     form elicitation, se vuole vedere tutti i poll, solo gli ACTIVE o solo gli EXPIRED.
     */
    public PollListPageResponseDto getPolls(String search, int page, int size, PollStatus statusFilter) {
        validatePaging(page, size);

        Pageable pageable = PageRequest.of(page, size); // tradotto poi in query con LIMIT e OFFSET
        boolean hasSearch = search != null && !search.isBlank();

        Page<Poll> pollPage;
        if (statusFilter == null) {
            pollPage = hasSearch
                    ? pollRepository.findByQuestionContainingIgnoreCase(search, pageable)
                    : pollRepository.findAll(pageable);
        } else {
            pollPage = hasSearch
                    ? pollRepository.findByStatusAndQuestionContainingIgnoreCase(statusFilter, search, pageable)
                    : pollRepository.findByStatus(statusFilter, pageable);
        }

        // mapping di Page<Poll> -> PollListPageResponseDto
        PollListPageResponseDto response = new PollListPageResponseDto();
        response.setFirst(pollPage.isFirst());
        response.setLast(pollPage.isLast());
        response.setSize(pollPage.getSize());
        response.setTotalElements(pollPage.getTotalElements());
        response.setTotalPages(pollPage.getTotalPages());
        response.setNumber(pollPage.getNumber());

        // mapping dei singoli Poll -> PollResponseDto (con controllo di scadenza lazy: uno status ACTIVE
        // stantio nel DB, non ancora aggiornato dal batch notturno, viene corretto qui prima di mostrarlo)
        List<PollResponseDto> contents = pollPage.getContent().stream()
                .map(this::ensureFreshStatus)
                .map(pollMapper::toResponseDto)
                .toList();
        response.setContents(contents);

        return response;
    }


    public PollListPageResponseDto getMyPolls(String search, int page, int size) {
        return getMyPolls(search, page, size, null);
    }

    /**
     * @param statusFilter se {@code null}, nessun filtro di stato viene applicato (equivalente ad "ALL").
     *                     Usato dal tool MCP {@code get_my_polls} dopo che l'utente ha scelto, tramite
     *                     form elicitation, se vuole vedere tutti i propri poll, solo gli ACTIVE o solo gli EXPIRED.
     */
    public PollListPageResponseDto getMyPolls(String search, int page, int size, PollStatus statusFilter) {
        validatePaging(page, size);

        Long currentUserId = securityUtils.currentUserId();

        Pageable pageable = PageRequest.of(page, size);
        boolean hasSearch = search != null && !search.isBlank();

        Page<Poll> pollPage;
        if (statusFilter == null) {
            pollPage = hasSearch
                    ? pollRepository.findByOwnerIdAndQuestionContainingIgnoreCase(currentUserId, search, pageable)
                    : pollRepository.findByOwnerId(currentUserId, pageable);
        } else {
            pollPage = hasSearch
                    ? pollRepository.findByOwnerIdAndStatusAndQuestionContainingIgnoreCase(
                            currentUserId, statusFilter, search, pageable)
                    : pollRepository.findByOwnerIdAndStatus(currentUserId, statusFilter, pageable);
        }

        PollListPageResponseDto response = new PollListPageResponseDto();

        response.setFirst(pollPage.isFirst());
        response.setLast(pollPage.isLast());
        response.setSize(pollPage.getSize());
        response.setTotalElements(pollPage.getTotalElements());
        response.setTotalPages(pollPage.getTotalPages());
        response.setNumber(pollPage.getNumber());

        List<PollResponseDto> contents = pollPage.getContent().stream()
                .map(this::ensureFreshStatus)
                .map(pollMapper::toResponseDto)
                .toList();

        response.setContents(contents);

        return response;
    }


    public PollResponseDto getPollById(Long id) {
        Poll poll = pollRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Poll with id {} not found", id);
                    return new PollNotFoundException("Poll not found");
                });

        return pollMapper.toResponseDto(poll);
    }

    public PollDetailsResponseDto getPollDetails(Long pollId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        log.info("=== VERIFICA CONTESTO ===");
        log.info("Classe Authentication: {}", auth.getClass().getName());
        log.info("Username (auth.getName()): {}", auth.getName());
        log.info("Authorities (Ruoli/Scope): {}", auth.getAuthorities());

        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            log.info("Claims totali nel JWT: {}", jwtAuth.getToken().getClaims());
        }
        log.info("=========================");


        Poll poll = pollRepository.findById(pollId)
                .orElseThrow(() -> new PollNotFoundException("Poll not found"));

        poll = ensureFreshStatus(poll);

        List<Option> options = optionRepository.findByPollId(pollId);

        return pollMapper.toDetailsResponseDto(poll, options);
    }

    @Transactional
    public PollResponseDto closePoll(Long pollId) {

        Poll poll = pollRepository.findById(pollId)
                .orElseThrow(() -> new PollNotFoundException("Poll not found"));

        if (!securityUtils.isAdminOrOwner(poll)) {
            log.warn(
                    "User {} attempted to close poll {} without permission",
                    securityUtils.currentUsername(),
                    pollId
            );

            throw new PollOwnershipException(
                    "Only the poll owner or an ADMIN can close this poll"
            );
        }

        // Controllo di scadenza lazy prima di verificare lo status: se il poll era già scaduto per
        // data ma non ancora marcato EXPIRED dal batch, questa chiamata lo formalizza subito, così il
        // controllo sottostante vede sempre lo stato reale (in tal caso rifiuterà la close esplicita
        // con "poll non attivo", perché di fatto è già stato chiuso appena sopra).
        poll = ensureFreshStatus(poll);

        if (poll.getStatus() != PollStatus.ACTIVE) {
            log.warn(
                    "User {} attempted to close non-active poll {} (status={})",
                    securityUtils.currentUsername(),
                    pollId,
                    poll.getStatus()
            );

            throw new PollNotModifiableException(
                    "Poll is not active and cannot be closed"
            );
        }

        pollWinnerService.processPoll(poll);

        Poll saved = pollRepository.save(poll);

        log.info(
                "Poll {} closed by {}",
                pollId,
                securityUtils.currentUsername()
        );

        return pollMapper.toResponseDto(saved);
    }

    @Transactional
    public PollResponseDto reopenPoll(Long pollId, LocalDateTime newExpiresAt) {

        Poll poll = pollRepository.findById(pollId)
                .orElseThrow(() -> new PollNotFoundException("Poll not found"));

        if (!securityUtils.isAdminOrOwner(poll)) {
            log.warn(
                    "User {} attempted to reopen poll {} without permission",
                    securityUtils.currentUsername(),
                    pollId
            );

            throw new PollOwnershipException(
                    "Only the poll owner or an ADMIN can reopen this poll"
            );
        }

        if (poll.getStatus() != PollStatus.EXPIRED) {
            log.warn(
                    "User {} attempted to reopen poll {} which is not expired (status={})",
                    securityUtils.currentUsername(),
                    pollId,
                    poll.getStatus()
            );

            throw new PollNotModifiableException(
                    "Only an expired poll can be reopened"
            );
        }

        if (newExpiresAt == null || newExpiresAt.isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("The new expiration date must be in the future");
        }

        // Riportare il poll ad ACTIVE richiede di azzerare l'esito calcolato in precedenza:
        // altrimenti un poll di nuovo votabile mostrerebbe ancora un vincitore "congelato".
        poll.setStatus(PollStatus.ACTIVE);
        poll.setWinnerOptionId(null);
        poll.setWinnerPercent(null);
        poll.setExpiresAt(newExpiresAt);

        Poll saved = pollRepository.save(poll);

        log.info(
                "Poll {} reopened by {} with new expiresAt={}",
                pollId,
                securityUtils.currentUsername(),
                newExpiresAt
        );

        return pollMapper.toResponseDto(saved);
    }

    public void checkCanDeletePoll(Long pollId) {
        Poll poll = pollRepository.findById(pollId)
                .orElseThrow(() -> new PollNotFoundException("Poll not found"));

        if (!securityUtils.isAdminOrOwner(poll)) {
            throw new PollOwnershipException(
                    "Only the poll owner or an ADMIN can delete this poll"
            );
        }
    }

    /**
     * Verifica la sola esistenza del poll, senza controlli di autorizzazione. Usato dal tool MCP
     * {@code request_delete_poll} per distinguere un record CONFIRMED "genuino" (poll ormai
     * definitivamente assente) da uno stale riferito a un id riutilizzato/ricreato (es. sequenza del
     * database che riparte da un valore già usato in passato, o dati reinseriti manualmente con id
     * espliciti): in quel caso l'id esiste di nuovo e la vecchia conferma non deve bloccare una
     * nuova richiesta di eliminazione.
     */
    public boolean pollExists(Long pollId) {
        return pollRepository.existsById(pollId);
    }

    @Transactional
    public void deletePoll(Long pollId) {

        Poll poll = pollRepository.findById(pollId)
                .orElseThrow(() -> new PollNotFoundException("Poll not found"));

        if (!securityUtils.isAdminOrOwner(poll)) {
            log.warn(
                    "User {} attempted to delete poll {} without permission",
                    securityUtils.currentUsername(),
                    pollId
            );

            throw new PollOwnershipException(
                    "Only the poll owner or an ADMIN can delete this poll"
            );
        }

        voteRepository.deleteByPollId(pollId);
        optionRepository.deleteByPollId(pollId);
        pollRepository.delete(poll);

        log.info(
                "Poll {} deleted by {}",
                pollId,
                securityUtils.currentUsername()
        );
    }
}