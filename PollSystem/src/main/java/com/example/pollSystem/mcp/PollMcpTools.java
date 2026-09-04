package com.example.pollSystem.mcp;

import com.example.pollSystem.dto.request.CreatePollRequestDto;
import com.example.pollSystem.dto.request.OptionRequestDto;
import com.example.pollSystem.dto.response.OptionResponseDto;
import com.example.pollSystem.dto.response.PollDetailsResponseDto;
import com.example.pollSystem.dto.response.PollListPageResponseDto;
import com.example.pollSystem.dto.response.PollResponseDto;
import com.example.pollSystem.dto.response.VoteResponseDto;
import com.example.pollSystem.entity.DeletePollRequest;
import com.example.pollSystem.entity.PollStatus;
import com.example.pollSystem.repository.VoteRepository;
import com.example.pollSystem.service.DeletePollRequestService;
import com.example.pollSystem.service.OptionService;
import com.example.pollSystem.service.PollService;
import com.example.pollSystem.service.VoteService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;


import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Component
public class PollMcpTools {

    private final PollService pollService;
    private final VoteRepository voteRepository;
    private final VoteService voteService;
    private final OptionService optionService;
    private final DeletePollRequestService deletePollRequestService;

    public PollMcpTools(PollService pollService, VoteRepository voteRepository, VoteService voteService,
                         OptionService optionService, DeletePollRequestService deletePollRequestService) {
        this.pollService = pollService;
        this.voteRepository = voteRepository;
        this.voteService = voteService;
        this.optionService = optionService;
        this.deletePollRequestService = deletePollRequestService;
    }

    // TOOLS PUBBLICI

    private static final int MAX_PAGE_SIZE = 50;

    /**
     * Chiede all'utente, tramite form-mode elicitation, quali poll vuole vedere: tutti, solo gli
     * ACTIVE o solo gli EXPIRED. Se il client MCP non supporta l'elicitation, o l'utente rifiuta/
     * annulla la form, il filtro non viene applicato (comportamento equivalente a "ALL"), in modo
     * che il tool resti comunque utilizzabile.
     */
    private PollStatus resolveStatusFilter(McpSyncRequestContext context, String subjectLabel) {
        if (!context.elicitEnabled()) {
            return null;
        }

        Map<String, Object> statusProperty = Map.of(
                "type", "string",
                "title", "Poll status",
                "description", "Which " + subjectLabel + " do you want to see?",
                "enum", List.of("ALL", "ACTIVE", "EXPIRED"),
                "default", "ALL"
        );
        Map<String, Object> requestedSchema = Map.of(
                "type", "object",
                "properties", Map.of("status", statusProperty),
                "required", List.of("status")
        );

        McpSchema.ElicitResult result = context.elicit(McpSchema.ElicitFormRequest.builder(
                "Select which " + subjectLabel + " you would like to see: all of them, only the active " +
                        "ones, or only the expired ones.",
                requestedSchema
        ).build());

        if (result.action() != McpSchema.ElicitResult.Action.ACCEPT || result.content() == null) {
            // Rifiutato/annullato dal client o dall'utente: nessun filtro, si mostra tutto.
            return null;
        }

        Object rawStatus = result.content().get("status");
        if (rawStatus == null || "ALL".equalsIgnoreCase(rawStatus.toString())) {
            return null;
        }

        try {
            return PollStatus.valueOf(rawStatus.toString().toUpperCase());
        } catch (IllegalArgumentException ex) {
            // Valore inatteso restituito dal client: si ignora il filtro piuttosto che fallire il tool.
            return null;
        }
    }

    @McpTool(
            name = "search_polls",
            description = "Query the live application database to search for polls using an optional text filter. " +
                    "Supports pagination. Note: Always include the unique database ID of each poll when presenting the list to the user, " +
                    "as it is required for referencing specific polls or accessing them via resources."
    )
    public PollListPageResponseDto searchPolls(
            McpSyncRequestContext context,
            @McpToolParam(description = "Optional text search query to filter poll titles", required = false) String search,
            @McpToolParam(description = "The page number to retrieve, starting from 0", required = true) int page,
            @McpToolParam(description = "The number of items to return per page (e.g., 10, max " + MAX_PAGE_SIZE + ")", required = true) int size) {
        PollStatus statusFilter = resolveStatusFilter(context, "polls");
        return pollService.getPolls(search, page, size, statusFilter);
    }

    @McpTool(
            name = "get_poll_details",
            description = "Retrieve the full structure of a specific poll by its database ID, including title, metadata, and available options."
    )
    public PollDetailsResponseDto getPollDetails(
            @McpToolParam(description = "The unique database ID of the poll", required = true) Long pollId) {
        return pollService.getPollDetails(pollId);
    }

    public record OptionVoteResult(Long optionId, String optionMessage, long voteCount) {}

    @McpTool(
            name = "get_poll_votes",
            description = "Retrieve the live real-time vote results for a specific poll. " +
                    "CRITICAL: Before performing any statistical analysis or presenting these results, " +
                    "you MUST read the instruction guide available at the MCP resource 'poll://analysis-guide' " +
                    "and follow its execution steps strictly."
    )
    public List<OptionVoteResult> getPollVotes(
            @McpToolParam(description = "The unique database ID of the poll", required = true) Long pollId) {
        PollDetailsResponseDto details = pollService.getPollDetails(pollId);
        return details.getOptions().stream()
                .map(opt -> new OptionVoteResult(
                        opt.getId(),
                        opt.getMessage(),
                        voteRepository.countByOptionId(opt.getId())))
                .toList();
    }


    // TOOLS PROTETTI CON ACCESSO CONTESTUALE (USER o ADMIN)

    @PreAuthorize("isAuthenticated()") // Chiunque sia loggato può votare (il Service bloccherà se è l'owner)
    @McpTool(
            name = "vote_poll",
            description = "Cast or update the authenticated user's vote for a specific option on an active poll. Calling this again with a different option overwrites the previous vote."
    )
    public OptionResponseDto votePoll(
            @McpToolParam(description = "The unique database ID of the poll to vote on", required = true) Long pollId,
            @McpToolParam(description = "The unique database ID of the option to vote for", required = true) Long optionId) {
        return voteService.voteOption(pollId, optionId);
    }


    @PreAuthorize("isAuthenticated()")
    @McpTool(
            name = "get_my_vote",
            description = "Retrieve the authenticated user's own vote (chosen option and timestamp) for a specific poll, if any. Fails if the user has not voted on that poll yet."
    )
    public VoteResponseDto getMyVote(
            @McpToolParam(description = "The unique database ID of the poll", required = true) Long pollId) {
        return voteService.getUserVoteForPoll(pollId);
    }


    @PreAuthorize("isAuthenticated()")
    @McpTool(
            name = "delete_my_vote",
            description = "Remove the authenticated user's own vote from a specific poll, allowing them to withdraw their participation or vote again later."
    )
    public String deleteMyVote(
            @McpToolParam(description = "The unique database ID of the poll", required = true) Long pollId) {
        voteService.deleteMyVote(pollId);
        return "Your vote on poll " + pollId + " has been removed.";
    }


    @PreAuthorize("isAuthenticated()")
    @McpTool(
            name = "get_my_polls",
            description = "Retrieve the polls created by the currently authenticated user. Supports optional text search and pagination. " +
                    "CRITICAL: Always include the unique database ID of each poll when presenting the list to the user, " +
                    "as it is required to reference your polls or access them via resource templates."
    )
    public PollListPageResponseDto getMyPolls(
            McpSyncRequestContext context,

            @McpToolParam(
                    description = "Optional text search query to filter the user's polls",
                    required = false
            )
            String search,

            @McpToolParam(
                    description = "The page number to retrieve, starting from 0",
                    required = true
            )
            int page,

            @McpToolParam(
                    description = "The number of polls to return per page (max " + MAX_PAGE_SIZE + ")",
                    required = true
            )
            int size) {

        PollStatus statusFilter = resolveStatusFilter(context, "your polls");
        return pollService.getMyPolls(search, page, size, statusFilter);
    }


    @PreAuthorize("isAuthenticated()")
    @McpTool(
            name = "create_poll",
            description = "Create a new active poll. After creating the poll, remember to add choice options using 'add_option'."
    )
    public PollResponseDto createPoll(
            @McpToolParam(
                    description = "The question of the poll",
                    required = true
            )
            String question,

            @McpToolParam(
                    description = "The future expiration date and time. You can accept natural language from the user (e.g., 'tomorrow at 5pm', 'next Friday at noon') and convert it into a standard ISO-8601 string.",
                    required = true
            )
            LocalDateTime expiresAt) {

        CreatePollRequestDto request = new CreatePollRequestDto();
        request.setQuestion(question);
        request.setExpiresAt(expiresAt);

        return pollService.createPoll(request);
    }


    @PreAuthorize("isAuthenticated()")
    @McpTool(
            name = "add_option",
            description = "Add a new option to an active poll. " +
                    "Only the poll owner or an ADMIN can add options."
    )
    public OptionResponseDto addOption(
            @McpToolParam(
                    description = "The unique database ID of the poll",
                    required = true
            )
            Long pollId,

            @McpToolParam(
                    description = "The text of the new poll option",
                    required = true
            )
            String message) {

        OptionRequestDto request = new OptionRequestDto();
        request.setMessage(message);

        return optionService.addOptionToPoll(pollId, request);
    }


    @PreAuthorize("isAuthenticated()")
    @McpTool(
            name = "update_option",
            description = "Update/Delete an option from an active poll. This action is only permitted if the poll is active and the specific option has zero votes."
    )
    public OptionResponseDto updateOption(
            @McpToolParam(
                    description = "The unique database ID of the poll",
                    required = true
            )
            Long pollId,

            @McpToolParam(
                    description = "The unique database ID of the option",
                    required = true
            )
            Long optionId,

            @McpToolParam(
                    description = "The new text of the option",
                    required = true
            )
            String message) {

        OptionRequestDto request = new OptionRequestDto();
        request.setMessage(message);

        return optionService.updateOption(
                pollId,
                optionId,
                request
        );
    }


    @PreAuthorize("isAuthenticated()")
    @McpTool(
            name = "delete_option",
            description = "Update/Delete an option from an active poll. This action is only permitted if the poll is active and the specific option has zero votes."
    )
    public String deleteOption(
            @McpToolParam(
                    description = "The unique database ID of the poll",
                    required = true
            )
            Long pollId,

            @McpToolParam(
                    description = "The unique database ID of the option to delete",
                    required = true
            )
            Long optionId) {

        optionService.deleteOption(pollId, optionId);
        return "Option " + optionId + " has been deleted from poll " + pollId + ".";
    }


    // Permette l'accesso se l'utente è ADMIN OPPURE se è l'owner del poll
    @PreAuthorize("isAuthenticated()")
    @McpTool(
            name = "close_poll",
            description = "Close an active poll, computing and assigning the winning option. " +
                    "Only the poll owner or an ADMIN can close the poll."
    )
    public PollResponseDto closePoll(
            @McpToolParam(
                    description = "The unique database ID of the poll to close",
                    required = true
            )
            Long pollId) {

        return pollService.closePoll(pollId);
    }


    // Permette l'accesso se l'utente è ADMIN OPPURE se è l'owner del poll
    @PreAuthorize("isAuthenticated()")
    @McpTool(
            name = "reopen_poll",
            description = "Reopen a closed/expired poll to make it active and collect new votes. This clears the previous winner but preserves existing votes. Requires a new expiration date."
    )
    public PollResponseDto reopenPoll(
            @McpToolParam(
                    description = "The unique database ID of the poll to reopen",
                    required = true
            )
            Long pollId,

            @McpToolParam(
                    description = "The new date and time when the poll expires again. You can accept natural language from the user (e.g., 'tomorrow at 5pm', 'next Friday at noon') and convert it into a standard ISO-8601 string. Must be in the future.",
                    required = true
            )
            LocalDateTime expiresAt) {

        return pollService.reopenPoll(pollId, expiresAt);
    }

    // NOTA: questo tool NON è registrato tramite l'annotazione @McpTool, ma manualmente su
    // McpSyncServer da DeletePollToolRegistrar. Motivo: spring-ai-mcp-annotations cattura SEMPRE
    // (di default, in modo non configurabile via @McpTool) qualunque eccezione lanciata da un
    // metodo @McpTool e la converte in un CallToolResult con isError=true, mai in un vero errore
    // JSON-RPC a livello di protocollo. Per poter restituire lo standard JSON-RPC error -32042
    // (URL_ELICITATION_REQUIRED, vedi McpError.URL_ELICITATION_REQUIRED / McpSchema.ErrorCodes)
    // l'eccezione McpError deve propagare non modificata fuori dal callHandler del tool, cosa che
    // avviene solo bypassando l'invocazione riflessiva usata dall'annotazione. Questo metodo resta
    // comunque protetto da @PreAuthorize, verificato regolarmente perché invocato dal registrar
    // attraverso il bean Spring (proxato), non tramite chiamata interna "this.".
    @PreAuthorize("isAuthenticated()")
    public String requestDeletePollLogic(Long pollId, String mcpSessionId) {

        // Rileva un eventuale retry della stessa tool/call PRIMA di ricontrollare l'esistenza/
        // eliminabilità del poll: se una precedente richiesta per questo poll e questo utente è già
        // stata CONFIRMED, il poll non esiste più nel database e chiamare subito
        // pollService.checkCanDeletePoll(pollId) fallirebbe con "Poll not found", un esito
        // fuorviante rispetto al reale stato (successo) dell'operazione. Interrogare prima questo
        // storico permette di restituire l'esito corretto direttamente dal tool, dato che la
        // notifica out-of-band notifications/elicitation/complete da sola non può veicolare
        // informazioni specifiche al chiamante.
        var latestRequest = deletePollRequestService.findLatestRequestForCurrentUser(pollId);
        if (latestRequest.isPresent()) {
            DeletePollRequest request = latestRequest.get();
            switch (request.getStatus()) {
                case CONFIRMED:
                    // Un record CONFIRMED è normalmente definitivo (id auto-incrementale, mai
                    // riassegnato). Si verifica comunque che il poll non esista di nuovo, per il caso
                    // (raro ma possibile: sequenza del database riavviata, dati reinseriti
                    // manualmente con id espliciti, ripristino da backup) in cui l'id sia stato
                    // riutilizzato per un poll diverso: in tal caso la vecchia conferma è stale e non
                    // deve bloccare la nuova richiesta di eliminazione.
                    if (!pollService.pollExists(pollId)) {
                        return "SUCCESS: Poll " + pollId + " has already been deleted (confirmed, elicitationId="
                                + request.getId() + ").";
                    }
                    break;
                case PENDING:
                    return "PENDING: A confirmation for poll " + pollId + " is already pending (elicitationId="
                            + request.getId() + "). Check your browser to finalize it, or call get_delete_poll_status.";
                case EXPIRED, CANCELLED:
                    // Richiesta ormai conclusa senza esito utile (scaduta o annullata): si procede
                    // come per una prima richiesta, creandone una nuova più sotto.
                    break;
            }
        }

        // Controllo preventivo: evita di innescare l'elicitation a chi non può
        // comunque eliminare il poll. L'autorizzazione viene ricontrollata più
        // avanti anche dall'endpoint REST di conferma, prima della cancellazione vera e propria.
        pollService.checkCanDeletePoll(pollId);

        // Memorizza la richiesta di eliminazione pending: verrà verificata e consumata
        // esclusivamente dall'endpoint REST di conferma (scope "rest"), mai da questo tool.
        DeletePollRequest pendingRequest = deletePollRequestService.createPendingRequest(pollId, mcpSessionId);
        String confirmationUrl = deletePollRequestService.buildConfirmationUrl(pendingRequest.getId());

        McpSchema.ElicitUrlRequest elicitUrlRequest = McpSchema.ElicitUrlRequest.builder(
                "Confirm deletion of poll " + pollId + " by opening the confirmation page in your browser.",
                confirmationUrl,
                pendingRequest.getId()
        ).build();

        // Standard JSON-RPC error -32042 (URL Elicitation Required, vedi specifica MCP): invece di
        // eseguire noi stessi un round-trip elicitation/create bloccante, la risposta stessa alla
        // tools/call segnala al client che è richiesta un'elicitation URL out-of-band. Il tool non
        // attende (né può forzare) che l'utente completi il flusso nel browser, e non esegue mai la
        // cancellazione: questo impedisce a un client MCP malevolo di confermare l'operazione per
        // conto dell'utente. L'esito reale va verificato via notifications/elicitation/complete
        // (best-effort) oppure interrogando get_delete_poll_status.
        throw McpError.URL_ELICITATION_REQUIRED.apply(List.of(elicitUrlRequest));
    }

    @PreAuthorize("isAuthenticated()")
    @McpTool(
             name = "get_delete_poll_status",
             description = "Check the status of a deletion request using the 'elicitationId'. Returns: PENDING (user hasn't confirmed yet, poll again later), CONFIRMED (deleted successfully), EXPIRED, or CANCELLED."
    )
    public String getDeletePollStatus(
            @McpToolParam(
                    description = "The elicitationId returned by a previous call to request_delete_poll",
                    required = true
            )
            String elicitationId) {

        return deletePollRequestService.getStatus(elicitationId).name();
    }
}