package com.example.pollSystem.exception;

/**
 * Sollevata quando la richiesta di eliminazione poll è scaduta (oltre il TTL previsto
 * per la conferma out-of-band) e quindi non può più essere confermata.
 */
public class DeletePollRequestExpiredException extends RuntimeException {
    public DeletePollRequestExpiredException(String message) {
        super(message);
    }
}
