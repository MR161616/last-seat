package com.lastseat.booking.repository;

import com.lastseat.booking.domain.Reservation;
import com.lastseat.booking.domain.ReservationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.id = :id")
    Optional<Reservation> lockById(@Param("id") Long id);

    @Query("select r from Reservation r join fetch r.seat join fetch r.user where r.id = :id")
    Optional<Reservation> findDetailed(@Param("id") Long id);

    @Query("select r from Reservation r join fetch r.seat where r.user.id = :userId order by r.reservedAt desc")
    List<Reservation> findHistoryForUser(@Param("userId") Long userId);

    @Query("select r from Reservation r where r.seat.id = :seatId and r.user.id = :userId and r.status in :live")
    Optional<Reservation> findLiveForUserAndSeat(@Param("userId") Long userId, @Param("seatId") Long seatId,
                                                 @Param("live") Collection<ReservationStatus> live);

    @Query("select r from Reservation r where r.seat.id = :seatId and r.status in :live")
    Optional<Reservation> findLiveForSeat(@Param("seatId") Long seatId, @Param("live") Collection<ReservationStatus> live);

    /**
     * Expiry sweep. SKIP LOCKED means a hold that is being confirmed *right now* (row locked by
     * the confirm transaction) is simply skipped and picked up on the next sweep, so the sweeper
     * never blocks user requests and never races with them.
     */
    @Query(value = """
            select id from reservations
            where status = 'HELD' and expires_at < :now
            order by expires_at
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<Long> findExpiredIdsForUpdate(@Param("now") Instant now, @Param("limit") int limit);

    long countByStatus(ReservationStatus status);
}
