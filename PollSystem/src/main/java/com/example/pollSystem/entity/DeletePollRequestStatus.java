package com.example.pollSystem.entity;

/**
 * Stato di una richiesta di eliminazione poll in attesa di conferma
 * tramite URL-mode elicitation (vedi {@link DeletePollRequest}).
 */
public enum DeletePollRequestStatus {
    PENDING,
    CONFIRMED,
    EXPIRED,
    CANCELLED
}
