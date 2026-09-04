package com.example.pollSystem.dto.response;

import com.example.pollSystem.entity.PollStatus;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PollDetailsResponseDto {
    private Long id;

    private String owner;

    private LocalDateTime expiresAt;

    private PollStatus status;

    private WinnerOptionResponseDto winner; // può essere null se poll active

    private List<OptionResponseDto> options;
}
