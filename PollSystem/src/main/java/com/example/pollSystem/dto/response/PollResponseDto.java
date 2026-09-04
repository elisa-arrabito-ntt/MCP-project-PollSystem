package com.example.pollSystem.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class PollResponseDto {
    private Long id;
    private String question;
    private String owner;
    private LocalDateTime expiresAt;
    private String status;
}
