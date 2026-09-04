package com.example.pollSystem.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class WinnerOptionResponseDto {
    private Long pollId;
    private Long optionId;
    private Double percentOfWiner;
}