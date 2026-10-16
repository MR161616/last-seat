package com.lastseat.booking.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payments")
public class Payment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "reservation_id") private Reservation reservation;
    @Column(nullable = false) private BigDecimal amount;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private PaymentMode mode;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private PaymentStatus status;
    @Column(name = "settle_at") private Instant settleAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "processed_at") private Instant processedAt;

    protected Payment() {}
    public Payment(Reservation reservation, BigDecimal amount, PaymentMode mode, PaymentStatus status, Instant settleAt) {
        this.reservation = reservation; this.amount = amount; this.mode = mode; this.status = status; this.settleAt = settleAt;
        if (status != PaymentStatus.PENDING) this.processedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Reservation getReservation() { return reservation; }
    public BigDecimal getAmount() { return amount; }
    public PaymentMode getMode() { return mode; }
    public PaymentStatus getStatus() { return status; }
    public void setStatus(PaymentStatus status) { this.status = status; }
    public Instant getSettleAt() { return settleAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}
