package com.example.pollSystem.exception;

/**
 * Sollevata quando una richiesta di eliminazione poll non è (più) nello stato PENDING,
 * ad esempio perché già confermata o annullata in precedenza (non riutilizzabile).
 */
public class DeletePollRequestNotPendingException extends RuntimeException {
    public DeletePollRequestNotPendingException(String message) {
        super(message);
    }
}
