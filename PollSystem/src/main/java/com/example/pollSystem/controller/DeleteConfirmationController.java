package com.example.pollSystem.controller;

import com.example.pollSystem.service.DeletePollRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint REST di conferma per l'URL-mode elicitation di {@code delete_poll}.
 * <p>
 * Viene invocato dalla pagina statica di conferma ({@code confirm-delete.html}) al click
 * dell'utente, e richiede un token OAuth2 con scope "rest" (distinto dallo scope usato dal
 * client MCP), a vita breve e ottenuto direttamente dalla pagina stessa come client OAuth2
 * aggiuntivo. Nessun client MCP può invocare questo endpoint per conto dell'utente perché
 * non possiede (e non può richiedere) lo scope "rest".
 */
@RestController
@RequestMapping("/rest/api/v0")
@RequiredArgsConstructor
public class DeleteConfirmationController {

    private final DeletePollRequestService deletePollRequestService;

    @PreAuthorize("hasAuthority('SCOPE_rest')")
    @PostMapping("/delete-poll-requests/{elicitationId}/confirm")
    public ResponseEntity<Void> confirmDeletePoll(@PathVariable String elicitationId) {
        deletePollRequestService.confirmAndDelete(elicitationId);
        return ResponseEntity.noContent().build();
    }
}
