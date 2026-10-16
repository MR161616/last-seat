package com.lastseat.booking.repository;

import com.lastseat.booking.domain.Seat;
import com.lastseat.booking.domain.SeatStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    /**
     * SELECT ... FOR UPDATE. The calling transaction owns this seat row until commit;
     * every other reserve attempt on the same seat blocks here and then sees the new status.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.id = :id")
    Optional<Seat> lockById(@Param("id") Long id);

    List<Seat> findAllByOrderBySectionAscRowNoAscSeatNoAsc();
    List<Seat> findByStatusOrderBySectionAscRowNoAscSeatNoAsc(SeatStatus status);
    long countByStatus(SeatStatus status);

}
