package com.example.pollSystem.controller;

import com.example.pollSystem.dto.response.OptionResponseDto;
import com.example.pollSystem.dto.response.VoteResponseDto;
import com.example.pollSystem.service.VoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/rest/api/v0")
@RequiredArgsConstructor
public class VoteController {

    private final VoteService voteService;

    @PutMapping("/polls/{id}/options/{optionId}/vote")
    public ResponseEntity<OptionResponseDto> voteOption(
            @PathVariable("id") Long pollId,
            @PathVariable Long optionId
    ) {
        OptionResponseDto response = voteService.voteOption(pollId, optionId);
        return ResponseEntity.status(201).body(response);
    }

    @GetMapping("/polls/{id}/vote")
    public ResponseEntity<VoteResponseDto> getUserVoteForPoll(@PathVariable("id") Long pollId) {
        VoteResponseDto response = voteService.getUserVoteForPoll(pollId);
        return ResponseEntity.ok(response);
    }
}