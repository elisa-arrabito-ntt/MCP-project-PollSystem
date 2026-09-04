package com.example.pollSystem.repository;

import com.example.pollSystem.entity.Poll;
import com.example.pollSystem.entity.PollStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface PollRepository extends JpaRepository<Poll, Long> {

    // genera una query tipo WHERE lower(question) LIKE lower('%search%')
    // Pageable consente di specificare page e size, tradotto in SQL con LIMIT e OFFSET
    Page<Poll> findByQuestionContainingIgnoreCase(String search, Pageable pageable);

    // Genera automaticamente un controllo EXISTS ottimizzato nel DB
    boolean existsByIdAndOwnerId(Long id, Long ownerId);

    List<Poll> findByExpiresAtBetween(LocalDateTime startOfDay, LocalDateTime endOfDay);

    Page<Poll> findByOwnerId(Long ownerId, Pageable pageable);

    Page<Poll> findByOwnerIdAndQuestionContainingIgnoreCase(
            Long ownerId,
            String search,
            Pageable pageable
    );

    // Varianti con filtro esplicito sullo status, usate dai tool MCP di ricerca
    // dopo che l'utente ha scelto (via form elicitation) quali poll vuole vedere.
    Page<Poll> findByStatus(PollStatus status, Pageable pageable);

    Page<Poll> findByStatusAndQuestionContainingIgnoreCase(
            PollStatus status,
            String search,
            Pageable pageable
    );

    Page<Poll> findByOwnerIdAndStatus(Long ownerId, PollStatus status, Pageable pageable);

    Page<Poll> findByOwnerIdAndStatusAndQuestionContainingIgnoreCase(
            Long ownerId,
            PollStatus status,
            String search,
            Pageable pageable
    );
}