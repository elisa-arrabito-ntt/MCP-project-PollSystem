package com.example.pollSystem.dto.response;

import lombok.Data;

import java.util.List;

// per GET /polls paginato
@Data
public class PollListPageResponseDto {
    private boolean first;
    private boolean last;
    private int size;
    private long totalElements;
    private int totalPages;
    private int number;
    private List<PollResponseDto> contents;
}