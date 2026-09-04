package com.example.pollSystem.service;

import com.example.pollSystem.entity.Option;
import com.example.pollSystem.entity.Poll;
import com.example.pollSystem.entity.PollStatus;
import com.example.pollSystem.repository.OptionRepository;
import com.example.pollSystem.repository.VoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PollWinnerService {

    private final OptionRepository optionRepository;
    private final VoteRepository voteRepository;

    public void processPoll(Poll poll) {
        // 1. Recupera le options del poll
        List<Option> options = optionRepository.findByPollId(poll.getId());

        long totalVotes = 0L;
        long maxVotes = 0L;
        Long winnerOptionId = null;

        // 2. Conta voti per ogni option e trova il max
        for (Option option : options) {
            long votes = voteRepository.countByOptionId(option.getId());
            totalVotes += votes;

            if (votes > maxVotes) {
                maxVotes = votes;
                winnerOptionId = option.getId();
            }
        }

        // 3. Se nessuno ha votato, non setto winner
        if (totalVotes == 0L) {
            poll.setStatus(PollStatus.EXPIRED);
            poll.setWinnerOptionId(null);
            poll.setWinnerPercent(null);
            // Il chiamante (batch job) penserà a salvare il poll
            return;
        }

        // 4. Calcola la percentuale del vincitore
        double winnerPercent = (maxVotes * 100.0) / totalVotes;

        // 5. Aggiorna il poll
        poll.setStatus(PollStatus.EXPIRED);
        poll.setWinnerOptionId(winnerOptionId);
        poll.setWinnerPercent(winnerPercent);
    }
}
