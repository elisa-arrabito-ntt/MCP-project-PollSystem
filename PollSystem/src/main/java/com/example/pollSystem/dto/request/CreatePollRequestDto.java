package com.example.pollSystem.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CreatePollRequestDto {
    @NotBlank
    private String question;

    @NotNull
    private LocalDateTime expiresAt;
}