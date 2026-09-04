package com.example.pollSystem.dto.response;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class OptionResponseDto {

    private Long id;
    private String message;
    private LocalDateTime createdAt;
}