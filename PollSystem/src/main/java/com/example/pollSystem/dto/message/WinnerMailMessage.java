package com.example.pollSystem.dto.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@Builder
@AllArgsConstructor
public class WinnerMailMessage {

    private String pollQuestion;
    private String winnerOption;
    private Double winnerPercent;
    private LocalDate expiredAt;
    private String ownerEmail;
}