package com.example.pollSystem.dto.response;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ValidationErrorResponseDto {

    private LocalDateTime timestamp;
    private int status;
    private String message;
    private String path;
}