package com.example.pollSystem.batch;

import com.example.pollSystem.dto.message.WinnerMailMessage;
import com.example.pollSystem.entity.Option;
import com.example.pollSystem.entity.Poll;
import com.example.pollSystem.repository.OptionRepository;
import com.example.pollSystem.repository.PollRepository;
import com.example.pollSystem.service.PollWinnerService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PollBatchJob {

    private final PollRepository pollRepository;
    private final PollWinnerService pollWinnerService;
    private final RabbitTemplate rabbitTemplate;
    private final OptionRepository optionRepository;

    @Scheduled(cron = "0 0 0 * * *") // ogni giorno alle 00:00
    public void processExpiringPolls() {
        log.info("Starting PollBatchJob...");

        LocalDate today = LocalDate.now();
        LocalDateTime start = today.atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay().minusNanos(1);

        List<Poll> polls = pollRepository.findByExpiresAtBetween(start, end);

        log.info("Found {} polls expiring today", polls.size());

        for (Poll poll : polls) {
            pollWinnerService.processPoll(poll);
            pollRepository.save(poll);

            log.info("Poll {} marked as EXPIRED with winner optionId={} percent={}",
                    poll.getId(), poll.getWinnerOptionId(), poll.getWinnerPercent());

            // se non c'è vincitore (zero voti), salto l'invio
            if (poll.getWinnerOptionId() == null) {
                log.info("Poll {} has no winner (no votes). Skipping RabbitMQ message.", poll.getId());
                continue;
            }

            Option winnerOption = optionRepository
                    .findById(poll.getWinnerOptionId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Winner option not found for poll " + poll.getId()));

            WinnerMailMessage message = WinnerMailMessage.builder()
                    .pollQuestion(poll.getQuestion())
                    .winnerOption(winnerOption.getMessage())
                    .winnerPercent(poll.getWinnerPercent())
                    .expiredAt(poll.getExpiresAt().toLocalDate())
                    .ownerEmail(poll.getOwner().getEmail())
                    .build();

            // serializzo in JSON e invio sulla coda poll.winner.mail
            rabbitTemplate.convertAndSend("poll.winner.mail", message);

            log.info("Sent RabbitMQ message for poll {} to queue poll.winner.mail", poll.getId());
        }


    }
}