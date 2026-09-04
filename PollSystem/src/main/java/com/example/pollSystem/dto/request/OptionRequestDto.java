package com.example.pollSystem.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class OptionRequestDto {

    @NotBlank
    private String message;
}