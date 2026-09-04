package com.example.pollSystem.exception;

public class DeletePollRequestNotFoundException extends RuntimeException {
    public DeletePollRequestNotFoundException(String message) {
        super(message);
    }
}
