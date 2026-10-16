package com.lastseat.booking.service;

import com.lastseat.booking.domain.BookingEvent;
import com.lastseat.booking.domain.Reservation;
import com.lastseat.booking.repository.BookingEventRepository;
import org.springframework.stereotype.Component;

@Component
public class EventLogger {
    private final BookingEventRepository events;
    public EventLogger(BookingEventRepository events) { this.events = events; }

    public void log(Reservation r, String type, String details) {
        events.save(new BookingEvent(r.getId(), r.getSeat().getId(), r.getUser().getId(), type, details));
    }
    public void log(Long reservationId, Long seatId, Long userId, String type, String details) {
        events.save(new BookingEvent(reservationId, seatId, userId, type, details));
    }
}
