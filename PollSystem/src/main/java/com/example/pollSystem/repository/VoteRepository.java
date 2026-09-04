package com.example.pollSystem.repository;

import com.example.pollSystem.entity.Vote;
import com.example.pollSystem.entity.Poll;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoteRepository extends JpaRepository<Vote, Long> {

    // per sapere se l'utente ha già votato per quel poll
    Optional<Vote> findByPollAndUsername(Poll poll, String username);

    // per sapere se un'option ha voti (in quel caso dovrei vietare update/delete dell'option)
    long countByOptionId(Long optionId);

    // rimuove tutti i voti del poll prima di poterlo cancellare (evita violazioni di FK)
    void deleteByPollId(Long pollId);
}