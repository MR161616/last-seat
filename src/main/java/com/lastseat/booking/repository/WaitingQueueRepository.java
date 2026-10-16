package com.lastseat.booking.repository;

import com.lastseat.booking.domain.QueueStatus;
import com.lastseat.booking.domain.WaitingQueueEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WaitingQueueRepository extends JpaRepository<WaitingQueueEntry, Long> {

    Optional<WaitingQueueEntry> findFirstByUserIdAndStatus(Long userId, QueueStatus status);
    Optional<WaitingQueueEntry> findFirstByUserIdOrderByJoinedAtDesc(Long userId);
    long countByStatus(QueueStatus status);

    /** Pops the head of the queue atomically; concurrent releasers each get a different entry. */
    @Query(value = """
            select id from waiting_queue
            where status = 'WAITING'
            order by joined_at, id
            limit 1
            for update skip locked
            """, nativeQuery = true)
    Optional<Long> lockHeadId();

    @Query(value = """
            select count(*) + 1 from waiting_queue q
            where q.status = 'WAITING'
              and (q.joined_at, q.id) < (select w.joined_at, w.id from waiting_queue w where w.id = :entryId)
            """, nativeQuery = true)
    long positionOf(@Param("entryId") Long entryId);

    @Query("select q from WaitingQueueEntry q join fetch q.user where q.status = 'WAITING' order by q.joinedAt, q.id")
    List<WaitingQueueEntry> findWaitingInOrder();
}
