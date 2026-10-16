package com.lastseat.booking.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Append-only audit trail; powers the booking-history API. */
@Entity
@Table(name = "booking_events")
public class BookingEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "reservation_id") private Long reservationId;
    @Column(name = "seat_id") private Long seatId;
    @Column(name = "user_id") private Long userId;
    @Column(name = "event_type", nullable = false) private String eventType;
    private String details;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    protected BookingEvent() {}
    public BookingEvent(Long reservationId, Long seatId, Long userId, String eventType, String details) {
        this.reservationId = reservationId; this.seatId = seatId; this.userId = userId; this.eventType = eventType; this.details = details;
    }

    public Long getId() { return id; }
    public Long getReservationId() { return reservationId; }
    public Long getSeatId() { return seatId; }
    public Long getUserId() { return userId; }
    public String getEventType() { return eventType; }
    public String getDetails() { return details; }
    public Instant getCreatedAt() { return createdAt; }
}
