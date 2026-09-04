package com.example.pollSystem.dto.response;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class VoteResponseDto {
    private Long id;
    private Long optionId;
    private LocalDateTime votedAt;
}
