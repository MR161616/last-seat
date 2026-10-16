package com.lastseat.booking.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "reservations")
public class Reservation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id") private User user;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "seat_id") private Seat seat;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private ReservationStatus status = ReservationStatus.HELD;
    @Column(nullable = false) private String source = "DIRECT";
    @Column(name = "reserved_at", nullable = false) private Instant reservedAt = Instant.now();
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "confirmed_at") private Instant confirmedAt;
    @Column(name = "cancelled_at") private Instant cancelledAt;
    @Version private long version;

    protected Reservation() {}
    public Reservation(User user, Seat seat, Instant expiresAt, String source) {
        this.user = user; this.seat = seat; this.expiresAt = expiresAt; this.source = source;
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public Seat getSeat() { return seat; }
    public ReservationStatus getStatus() { return status; }
    public void setStatus(ReservationStatus status) { this.status = status; }
    public String getSource() { return source; }
    public Instant getReservedAt() { return reservedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }
    public long getVersion() { return version; }

    public boolean isExpired(Instant now) { return status == ReservationStatus.HELD && expiresAt.isBefore(now); }
}
