package com.example.pollSystem.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;

/**
 * Richiesta di eliminazione di un poll in attesa di conferma out-of-band da parte
 * dell'utente (URL-mode elicitation). Viene creata dal tool MCP {@code delete_poll}
 * e successivamente verificata e consumata dall'endpoint REST di conferma, protetto
 * da uno scope OAuth2 dedicato ("rest") distinto da quello usato dal client MCP ("mcp").
 * <p>
 * L'id coincide con l'{@code elicitationId} inviato al client MCP tramite
 * {@code McpSchema.ElicitUrlRequest}, così da poter correlare in modo univoco
 * la richiesta con la conferma ricevuta sull'endpoint REST.
 */
@Entity
@Table(name = "delete_poll_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeletePollRequest {

    @Id
    private String id;

    @Column(name = "poll_id", nullable = false)
    private Long pollId;

    @Column(name = "requested_by_user_id", nullable = false)
    private Long requestedByUserId;

    /**
     * Id della sessione MCP (Streamable HTTP) da cui è stato invocato {@code request_delete_poll},
     * usato per inviare, tramite {@code McpSyncServe#sendElicitationComplete}, la notifica
     * out-of-band di completamento (esito best-effort, mai fonte di verità: vedi
     * {@code get_delete_poll_status}). Può risultare non più valido se la sessione è nel
     * frattempo terminata: in tal caso l'invio fallisce silenziosamente.
     */
    @Column(name = "mcp_session_id")
    private String mcpSessionId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeletePollRequestStatus status;
}
