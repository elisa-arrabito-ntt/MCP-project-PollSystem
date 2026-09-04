package com.example.pollSystem.service;

import com.example.pollSystem.dto.request.OptionRequestDto;
import com.example.pollSystem.dto.response.OptionResponseDto;
import com.example.pollSystem.entity.Option;
import com.example.pollSystem.entity.Poll;
import com.example.pollSystem.entity.PollStatus;
import com.example.pollSystem.exception.*;
import com.example.pollSystem.mapper.OptionMapper;
import com.example.pollSystem.repository.OptionRepository;
import com.example.pollSystem.repository.PollRepository;
import java.time.LocalDateTime;

import com.example.pollSystem.repository.VoteRepository;
import com.example.pollSystem.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OptionService {

    private final PollRepository pollRepository;
    private final VoteRepository voteRepository;
    private final OptionRepository optionRepository;
    private final OptionMapper optionMapper;
    private final SecurityUtils securityUtils;
    private final PollService pollService;

    @Transactional
    public OptionResponseDto addOptionToPoll(Long pollId, OptionRequestDto request) {

        Poll poll = pollRepository.findById(pollId)
                .orElseThrow(() -> {
                    log.warn("Poll with id {} not found when adding option", pollId);
                    return new PollNotFoundException("Poll not found");
                });

        // ADMIN oppure OWNER
        if (!securityUtils.isAdminOrOwner(poll)) {
            log.warn(
                    "User {} attempted to add option to poll {} without permission",
                    securityUtils.currentUsername(),
                    pollId
            );

            throw new PollOwnershipException(
                    "Only the poll owner or an ADMIN can add options to this poll"
            );
        }

        // Controllo di scadenza lazy: aggiorna lo status a EXPIRED se il poll è scaduto per data
        // ma non ancora marcato come tale dal batch notturno.
        poll = pollService.ensureFreshStatus(poll);

        // Regola di business
        if (poll.getStatus() != PollStatus.ACTIVE) {
            log.warn(
                    "User {} attempted to add option to non-active poll {} (status={})",
                    securityUtils.currentUsername(),
                    pollId,
                    poll.getStatus()
            );

            throw new PollNotModifiableException(
                    "Cannot add options to a non-active poll"
            );
        }

        Option option = optionMapper.toEntity(request);

        option.setPoll(poll);
        option.setCreatedAt(LocalDateTime.now());

        Option saved = optionRepository.save(option);

        log.info(
                "Option '{}' added to poll {} by {}",
                saved.getMessage(),
                pollId,
                securityUtils.currentUsername()
        );

        return optionMapper.toResponseDto(saved);
    }


    @Transactional
    public OptionResponseDto updateOption(
            Long pollId,
            Long optionId,
            OptionRequestDto request) {

        Poll poll = pollRepository.findById(pollId)
                .orElseThrow(() -> {
                    log.warn(
                            "Poll with id {} not found when updating option",
                            pollId
                    );
                    return new PollNotFoundException("Poll not found");
                });

        Option option = optionRepository.findById(optionId)
                .orElseThrow(() -> {
                    log.warn(
                            "Option with id {} not found when updating on poll {}",
                            optionId,
                            pollId
                    );
                    return new OptionNotFoundException("Option not found");
                });

        // Verifica che l'option appartenga realmente al poll indicato
        if (!option.getPoll().getId().equals(poll.getId())) {
            log.warn(
                    "Option {} does not belong to poll {}: update rejected",
                    optionId,
                    pollId
            );

            throw new InvalidOptionOperationException(
                    "Option does not belong to the given poll"
            );
        }

        // ADMIN oppure OWNER
        if (!securityUtils.isAdminOrOwner(poll)) {
            log.warn(
                    "User {} attempted to update option {} in poll {} without permission",
                    securityUtils.currentUsername(),
                    optionId,
                    pollId
            );

            throw new PollOwnershipException(
                    "Only the poll owner or an ADMIN can update options in this poll"
            );
        }

        // Controllo di scadenza lazy: aggiorna lo status a EXPIRED se il poll è scaduto per data
        // ma non ancora marcato come tale dal batch notturno.
        poll = pollService.ensureFreshStatus(poll);

        // Regola di business
        if (poll.getStatus() != PollStatus.ACTIVE) {
            log.warn(
                    "User {} attempted to update option {} in non-active poll {}",
                    securityUtils.currentUsername(),
                    optionId,
                    pollId
            );

            throw new PollNotModifiableException(
                    "Cannot update options in a non-active poll"
            );
        }

        // Un'opzione con voti non può essere modificata
        long voteCount = voteRepository.countByOptionId(optionId);

        if (voteCount > 0) {
            log.warn(
                    "User {} attempted to update option {} with {} votes on poll {}",
                    securityUtils.currentUsername(),
                    optionId,
                    voteCount,
                    pollId
            );

            throw new OptionNotModifiableException(
                    "Cannot update an option that already has votes"
            );
        }

        option.setMessage(request.getMessage());

        Option updated = optionRepository.save(option);

        log.info(
                "Option '{}' updated in poll {} by {}",
                updated.getMessage(),
                pollId,
                securityUtils.currentUsername()
        );

        return optionMapper.toResponseDto(updated);
    }


    @Transactional
    public void deleteOption(Long pollId, Long optionId) {

        Poll poll = pollRepository.findById(pollId)
                .orElseThrow(() -> {
                    log.warn(
                            "Poll with id {} not found when deleting option {}",
                            pollId,
                            optionId
                    );
                    return new PollNotFoundException("Poll not found");
                });

        Option option = optionRepository.findById(optionId)
                .orElseThrow(() -> {
                    log.warn(
                            "Option with id {} not found when deleting on poll {}",
                            optionId,
                            pollId
                    );
                    return new OptionNotFoundException("Option not found");
                });

        // Verifica appartenenza
        if (!option.getPoll().getId().equals(poll.getId())) {
            log.warn(
                    "Option {} does not belong to poll {}: delete rejected",
                    optionId,
                    pollId
            );

            throw new InvalidOptionOperationException(
                    "Option does not belong to the given poll"
            );
        }

        // ADMIN oppure OWNER
        if (!securityUtils.isAdminOrOwner(poll)) {
            log.warn(
                    "User {} attempted to delete option {} from poll {} without permission",
                    securityUtils.currentUsername(),
                    optionId,
                    pollId
            );

            throw new PollOwnershipException(
                    "Only the poll owner or an ADMIN can delete options from this poll"
            );
        }

        // Controllo di scadenza lazy: aggiorna lo status a EXPIRED se il poll è scaduto per data
        // ma non ancora marcato come tale dal batch notturno.
        poll = pollService.ensureFreshStatus(poll);

        // Regola di business
        if (poll.getStatus() != PollStatus.ACTIVE) {
            log.warn(
                    "User {} attempted to delete option {} from non-active poll {}",
                    securityUtils.currentUsername(),
                    optionId,
                    pollId
            );

            throw new PollNotModifiableException(
                    "Cannot delete options from a non-active poll"
            );
        }

        // Un'opzione con voti non può essere eliminata
        long voteCount = voteRepository.countByOptionId(optionId);

        if (voteCount > 0) {
            log.warn(
                    "User {} attempted to delete option {} with {} votes on poll {}",
                    securityUtils.currentUsername(),
                    optionId,
                    voteCount,
                    pollId
            );

            throw new OptionNotModifiableException(
                    "Cannot delete an option that already has votes"
            );
        }

        optionRepository.delete(option);

        log.info(
                "Option {} deleted from poll {} by {}",
                optionId,
                pollId,
                securityUtils.currentUsername()
        );
    }
}