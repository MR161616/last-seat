package com.lastseat.booking.repository;

import com.lastseat.booking.domain.BookingEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface BookingEventRepository extends JpaRepository<BookingEvent, Long> {
    List<BookingEvent> findByReservationIdOrderByCreatedAtAsc(Long reservationId);
    List<BookingEvent> findTop100ByUserIdOrderByCreatedAtDesc(Long userId);
}
