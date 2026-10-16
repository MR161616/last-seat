package com.lastseat.booking.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "waiting_queue")
public class WaitingQueueEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id") private User user;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private QueueStatus status = QueueStatus.WAITING;
    @Column(name = "joined_at", nullable = false) private Instant joinedAt = Instant.now();
    @Column(name = "assigned_at") private Instant assignedAt;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "reservation_id") private Reservation reservation;

    protected WaitingQueueEntry() {}
    public WaitingQueueEntry(User user) { this.user = user; }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public QueueStatus getStatus() { return status; }
    public void setStatus(QueueStatus status) { this.status = status; }
    public Instant getJoinedAt() { return joinedAt; }
    public Instant getAssignedAt() { return assignedAt; }
    public void setAssignedAt(Instant assignedAt) { this.assignedAt = assignedAt; }
    public Reservation getReservation() { return reservation; }
    public void setReservation(Reservation reservation) { this.reservation = reservation; }
}
