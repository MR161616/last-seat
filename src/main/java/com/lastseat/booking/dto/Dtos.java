package com.lastseat.booking.dto;

import com.lastseat.booking.domain.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** All request/response records of the REST API in one place. */
public final class Dtos {
    private Dtos() {}

    // ---------- requests ----------
    public record CreateUserRequest(@NotBlank String name, @NotBlank @Email String email) {}
    public record ReserveRequest(@NotNull Long userId, @NotNull Long seatId) {}
    public record ConfirmRequest(@NotNull Long userId, PaymentMode paymentMode) {}
    public record CancelRequest(@NotNull Long userId) {}
    public record QueueJoinRequest(@NotNull Long userId) {}

    // ---------- responses ----------
    public record UserResponse(Long id, String name, String email, Instant createdAt) {
        public static UserResponse of(User u) { return new UserResponse(u.getId(), u.getName(), u.getEmail(), u.getCreatedAt()); }
    }

    public record SeatResponse(Long id, String seatNumber, String section, int row, int number, BigDecimal price, SeatStatus status) {
        public static SeatResponse of(Seat s) {
            return new SeatResponse(s.getId(), s.getSeatNumber(), s.getSection(), s.getRowNo(), s.getSeatNo(), s.getPrice(), s.getStatus());
        }
    }

    public record SeatSummary(long total, long available, long held, long booked, long waitingInQueue, boolean atCapacity) {}

    public record PaymentResponse(Long id, BigDecimal amount, PaymentMode mode, PaymentStatus status,
                                  Instant createdAt, Instant settleAt, Instant processedAt) {
        public static PaymentResponse of(Payment p) {
            return new PaymentResponse(p.getId(), p.getAmount(), p.getMode(), p.getStatus(), p.getCreatedAt(), p.getSettleAt(), p.getProcessedAt());
        }
    }

    public record ReservationResponse(Long id, Long userId, Long seatId, String seatNumber, String section, BigDecimal price,
                                      ReservationStatus status, String source, Instant reservedAt, Instant expiresAt,
                                      long secondsRemaining, Instant confirmedAt, Instant cancelledAt,
                                      PaymentResponse payment, String message) {
        public static ReservationResponse of(Reservation r, Payment p, String message) {
            Seat s = r.getSeat();
            long remaining = 0;
            if (r.getStatus() == ReservationStatus.HELD || r.getStatus() == ReservationStatus.PAYMENT_PENDING) {
                remaining = Math.max(0, Duration.between(Instant.now(), r.getExpiresAt()).getSeconds());
            }
            return new ReservationResponse(r.getId(), r.getUser().getId(), s.getId(), s.getSeatNumber(), s.getSection(), s.getPrice(),
                    r.getStatus(), r.getSource(), r.getReservedAt(), r.getExpiresAt(), remaining, r.getConfirmedAt(), r.getCancelledAt(),
                    p == null ? null : PaymentResponse.of(p), message);
        }
    }

    public record EventResponse(Long id, Long reservationId, Long seatId, Long userId, String eventType, String details, Instant createdAt) {
        public static EventResponse of(BookingEvent e) {
            return new EventResponse(e.getId(), e.getReservationId(), e.getSeatId(), e.getUserId(), e.getEventType(), e.getDetails(), e.getCreatedAt());
        }
    }

    public record QueueEntryResponse(Long id, Long userId, QueueStatus status, Long position, long waitingAhead,
                                     Instant joinedAt, Instant assignedAt, ReservationResponse assignedReservation, String message) {}

    public record QueueSummary(long waiting, boolean atCapacity, List<Map<String, Object>> head) {}

    public record ErrorResponse(int status, String error, String message, Instant timestamp) {}
}
