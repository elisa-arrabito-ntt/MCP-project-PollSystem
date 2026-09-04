package com.example.pollSystem.repository;

import com.example.pollSystem.entity.DeletePollRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeletePollRequestRepository extends JpaRepository<DeletePollRequest, String> {

    /**
     * Trova la richiesta più recente (qualunque stato) per la coppia (poll, utente richiedente),
     * usata per rilevare un retry della stessa tool/call {@code request_delete_poll}: sia quando
     * esiste già un'associazione PENDING non scaduta, sia quando la richiesta precedente è già stata
     * CONFIRMED (poll ormai eliminato, per cui un ricontrollo dell'esistenza del poll fallirebbe
     * fuorviantemente).
     */
    Optional<DeletePollRequest> findFirstByPollIdAndRequestedByUserIdOrderByCreatedAtDesc(
            Long pollId, Long requestedByUserId);
}
