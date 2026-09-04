package com.example.pollSystem.service;

import com.example.pollSystem.dto.response.OptionResponseDto;
import com.example.pollSystem.dto.response.VoteResponseDto;
import com.example.pollSystem.entity.Option;
import com.example.pollSystem.entity.Poll;
import com.example.pollSystem.entity.PollStatus;
import com.example.pollSystem.entity.Vote;
import com.example.pollSystem.exception.InvalidVoteException;
import com.example.pollSystem.exception.OptionNotFoundException;
import com.example.pollSystem.exception.PollNotFoundException;
import com.example.pollSystem.exception.VoteNotFoundException;
import com.example.pollSystem.mapper.OptionMapper;
import com.example.pollSystem.mapper.VoteMapper;
import com.example.pollSystem.repository.OptionRepository;
import com.example.pollSystem.repository.PollRepository;
import com.example.pollSystem.repository.VoteRepository;
import com.example.pollSystem.utils.SecurityUtils;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class VoteService {

    private final PollRepository pollRepository;
    private final OptionRepository optionRepository;
    private final VoteRepository voteRepository;
    private final OptionMapper optionMapper;
    private final VoteMapper voteMapper;
    private final SecurityUtils securityUtils;
    private final PollService pollService;

    @Transactional
    public OptionResponseDto voteOption(Long pollId, Long optionId) {
        Poll poll = pollRepository.findById(pollId)
                .orElseThrow(() -> {
                    log.warn("Poll with id {} not found when voting", pollId);
                    return new PollNotFoundException("Poll not found");
                });

        Option option = optionRepository.findById(optionId)
                .orElseThrow(() -> {
                    log.warn("Option with id {} not found when voting on poll {}", optionId, pollId);
                    return new OptionNotFoundException("Option not found");
                });

        // Verifica che l'option appartenga al poll
        if (!option.getPoll().getId().equals(poll.getId())) {
            log.warn("Option {} does not belong to poll {}: vote rejected", optionId, pollId);
            throw new InvalidVoteException("Option does not belong to the given poll");
        }

        String currentUser = securityUtils.currentUsername();
        Long currentUserId = securityUtils.currentUserId();

        if (poll.getOwner().getId().equals(currentUserId)) {
            log.warn("Owner {} attempted to vote on own poll {}", currentUser, pollId);
            throw new InvalidVoteException("Owner cannot vote own poll");
        }

        // Controllo di scadenza lazy: aggiorna lo status a EXPIRED se il poll è scaduto per data
        // ma non ancora marcato come tale dal batch notturno.
        poll = pollService.ensureFreshStatus(poll);

        if (poll.getStatus() != PollStatus.ACTIVE) {
            log.warn("User {} attempted to vote on non-active poll {} (status={})",
                    currentUser, pollId, poll.getStatus());
            throw new InvalidVoteException("Cannot vote on a non-active poll");
        }

        // Ricerca di un eventuale voto già esistente per questo poll da parte dell' utente autenticato
        Poll finalPoll = poll;
        Vote vote = voteRepository.findByPollAndUsername(poll, currentUser)
                .orElseGet(() -> {
                    // Nessun voto esistente, creazione di un nuovo voto
                    log.info("User {} is voting for the first time on poll {}", currentUser, pollId);
                    Vote newVote = new Vote();
                    newVote.setPoll(finalPoll);
                    newVote.setUsername(currentUser);
                    return newVote;
                });

        // Impostazione / aggiornamento di option e data del voto
        vote.setOption(option);
        vote.setVotedAt(LocalDateTime.now());

        // Salvataggio (crea o aggiorna)
        Vote saved = voteRepository.save(vote);

        log.info("User {} voted option {} on poll {} (voteId={})",
                currentUser, optionId, pollId, saved.getId());

        // Lo swagger richiede response schema Option
        return optionMapper.toResponseDto(option);
    }


    public VoteResponseDto getUserVoteForPoll(Long pollId) {
        Poll poll = pollRepository.findById(pollId)
                .orElseThrow(() -> new PollNotFoundException("Poll not found"));

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String currentUser = auth.getName();

        Vote vote = voteRepository.findByPollAndUsername(poll, currentUser)
                .orElseThrow(() -> new VoteNotFoundException("No vote for this poll"));

        return voteMapper.toResponseDto(vote);
    }

    @Transactional
    public void deleteMyVote(Long pollId) {
        Poll poll = pollRepository.findById(pollId)
                .orElseThrow(() -> new PollNotFoundException("Poll not found"));

        String currentUser = securityUtils.currentUsername();

        // Controllo di scadenza lazy: aggiorna lo status a EXPIRED se il poll è scaduto per data
        // ma non ancora marcato come tale dal batch notturno.
        poll = pollService.ensureFreshStatus(poll);

        if (poll.getStatus() != PollStatus.ACTIVE) {
            log.warn("User {} attempted to remove their vote on non-active poll {} (status={})",
                    currentUser, pollId, poll.getStatus());
            throw new InvalidVoteException("Cannot remove a vote from a non-active poll");
        }

        Vote vote = voteRepository.findByPollAndUsername(poll, currentUser)
                .orElseThrow(() -> new VoteNotFoundException("No vote for this poll"));

        voteRepository.delete(vote);

        log.info("User {} removed their vote (voteId={}) on poll {}", currentUser, vote.getId(), pollId);
    }

}