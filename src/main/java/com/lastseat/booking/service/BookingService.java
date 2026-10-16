package com.lastseat.booking.service;

import com.lastseat.booking.config.AppProperties;
import com.lastseat.booking.domain.*;
import com.lastseat.booking.dto.Dtos.*;
import com.lastseat.booking.repository.*;
import com.lastseat.booking.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Core booking workflow. Every public method is one database transaction, and inside it the
 * rows that matter are locked with SELECT ... FOR UPDATE, so two users racing for one seat are
 * serialised by PostgreSQL: the second one waits, then sees the seat is gone and gets a 409.
 *
 * Lock order (to avoid deadlocks): reservation row -> seat row -> waiting_queue row.
 */
@Service
public class BookingService {
    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    static final Set<ReservationStatus> LIVE = EnumSet.of(ReservationStatus.HELD, ReservationStatus.PAYMENT_PENDING, ReservationStatus.CONFIRMED);

    private final SeatRepository seats;
    private final ReservationRepository reservations;
    private final PaymentRepository payments;
    private final UserRepository users;
    private final WaitingQueueRepository queue;
    private final PaymentGateway gateway;
    private final EventLogger events;
    private final BookingEventRepository eventRepo;
    private final AppProperties props;

    public BookingService(SeatRepository seats, ReservationRepository reservations, PaymentRepository payments,
                          UserRepository users, WaitingQueueRepository queue, PaymentGateway gateway,
                          EventLogger events, BookingEventRepository eventRepo, AppProperties props) {
        this.seats = seats; this.reservations = reservations; this.payments = payments; this.users = users;
        this.queue = queue; this.gateway = gateway; this.events = events; this.eventRepo = eventRepo; this.props = props;
    }

    // ------------------------------------------------------------------ reserve

    public record ReserveResult(ReservationResponse reservation, boolean created) {}

    @Transactional
    public ReserveResult reserve(Long userId, Long seatId) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User " + userId + " not found"));

        // 1. Lazy expiry. If the seat's current hold has timed out but the sweeper hasn't run yet,
        //    release it now. Lock order is always reservation -> seat, so we lock the stale hold first.
        reservations.findLiveForSeat(seatId, LIVE).filter(r -> r.isExpired(Instant.now())).ifPresent(stale -> {
            Reservation locked = reservations.lockById(stale.getId()).orElseThrow();
            if (locked.isExpired(Instant.now())) {
                Seat s = seats.lockById(seatId).orElseThrow();
                expireLocked(locked, s, true);
            }
        });

        // 2. Lock the seat row. Concurrent requests for this seat queue up on this line and,
        //    once they get through, see whatever the winner committed.
        Seat seat = seats.lockById(seatId).orElseThrow(() -> ApiException.notFound("Seat " + seatId + " not found"));

        // 3. Repeated request from the same user -> return the existing hold (idempotent).
        var mine = reservations.findLiveForUserAndSeat(userId, seatId, LIVE);
        if (mine.isPresent()) {
            return new ReserveResult(toResponse(mine.get(), "You already hold this seat"), false);
        }

        if (seat.getStatus() != SeatStatus.AVAILABLE) {
            throw ApiException.conflict("Seat " + seat.getSeatNumber() + " is already " + seat.getStatus().name().toLowerCase());
        }

        // 4. Hold it.
        Reservation r = createHold(user, seat, "DIRECT");
        return new ReserveResult(toResponse(r, "Seat held for " + props.reservation().holdSeconds() + " seconds"), true);
    }

    /** Caller must already hold the seat row lock. */
    Reservation createHold(User user, Seat seat, String source) {
        seat.setStatus(SeatStatus.HELD);
        Instant expires = Instant.now().plusSeconds(props.reservation().holdSeconds());
        Reservation r = reservations.saveAndFlush(new Reservation(user, seat, expires, source)); // flush -> unique index check now
        events.log(r, "RESERVED", "Hold created via " + source + ", expires at " + expires);
        return r;
    }

    // ------------------------------------------------------------------ confirm / pay

    @Transactional
    public ReservationResponse confirm(Long reservationId, Long userId, PaymentMode mode) {
        Reservation r = reservations.lockById(reservationId).orElseThrow(() -> ApiException.notFound("Reservation " + reservationId + " not found"));
        if (!r.getUser().getId().equals(userId)) throw ApiException.forbidden("This reservation belongs to another user");
        Seat seat = seats.lockById(r.getSeat().getId()).orElseThrow();

        // idempotent replays
        switch (r.getStatus()) {
            case CONFIRMED -> { return toResponse(r, "Already confirmed"); }
            case PAYMENT_PENDING -> { return toResponse(r, "Payment is still being processed"); }
            case CANCELLED, EXPIRED, PAYMENT_FAILED ->
                    throw ApiException.gone("Reservation is " + r.getStatus().name().toLowerCase() + " and can no longer be confirmed");
            case HELD -> { /* continue */ }
        }
        if (r.isExpired(Instant.now())) {
            expireLocked(r, seat, true);
            throw ApiException.gone("Reservation expired before payment - the seat has been released");
        }

        PaymentGateway.Outcome outcome = gateway.charge(mode);
        Payment p = payments.save(new Payment(r, seat.getPrice(), mode == null ? PaymentMode.SUCCESS : mode, outcome.status(), outcome.settleAt()));
        events.log(r, "PAYMENT_ATTEMPTED", "mode=" + p.getMode() + " result=" + p.getStatus());

        switch (outcome.status()) {
            case SUCCESS -> markConfirmed(r, seat, p);
            case FAILED -> { paymentFailed(r, seat, p); }
            case PENDING -> {
                r.setStatus(ReservationStatus.PAYMENT_PENDING);
                r.setExpiresAt(outcome.settleAt().plusSeconds(5)); // hold survives until the gateway answers
                events.log(r, "PAYMENT_PENDING", "Gateway delayed; settles at " + outcome.settleAt());
            }
            default -> throw new IllegalStateException();
        }
        return toResponse(r, p, messageFor(r));
    }

    private void markConfirmed(Reservation r, Seat seat, Payment p) {
        r.setStatus(ReservationStatus.CONFIRMED);
        r.setConfirmedAt(Instant.now());
        seat.setStatus(SeatStatus.BOOKED);
        events.log(r, "CONFIRMED", "Payment " + p.getId() + " succeeded");
    }

    private void paymentFailed(Reservation r, Seat seat, Payment p) {
        r.setStatus(ReservationStatus.PAYMENT_FAILED);
        r.setCancelledAt(Instant.now());
        events.log(r, "PAYMENT_FAILED", "Payment " + p.getId() + " declined; seat released");
        releaseSeat(seat, "payment failure");
    }

    /** Settlement of DELAYED payments; invoked by the scheduler with the payment row already locked. */
    @Transactional
    public void settlePayment(Long paymentId) {
        Payment p = payments.findById(paymentId).orElseThrow();
        if (p.getStatus() != PaymentStatus.PENDING) return;
        Reservation r = reservations.lockById(p.getReservation().getId()).orElseThrow();
        Seat seat = seats.lockById(r.getSeat().getId()).orElseThrow();
        PaymentStatus result = gateway.settleDelayed();
        p.setStatus(result);
        p.setProcessedAt(Instant.now());
        if (r.getStatus() != ReservationStatus.PAYMENT_PENDING) {
            // user cancelled while the gateway was slow: refund if it ended up succeeding
            if (result == PaymentStatus.SUCCESS) { p.setStatus(PaymentStatus.REFUNDED); events.log(r, "REFUNDED", "Late success on a cancelled reservation"); }
            return;
        }
        if (result == PaymentStatus.SUCCESS) markConfirmed(r, seat, p); else paymentFailed(r, seat, p);
        log.info("Delayed payment {} settled as {} for reservation {}", p.getId(), p.getStatus(), r.getId());
    }

    // ------------------------------------------------------------------ cancel

    @Transactional
    public ReservationResponse cancel(Long reservationId, Long userId) {
        Reservation r = reservations.lockById(reservationId).orElseThrow(() -> ApiException.notFound("Reservation " + reservationId + " not found"));
        if (!r.getUser().getId().equals(userId)) throw ApiException.forbidden("This reservation belongs to another user");
        Seat seat = seats.lockById(r.getSeat().getId()).orElseThrow();

        if (r.getStatus() == ReservationStatus.CANCELLED) return toResponse(r, "Already cancelled");
        if (!r.getStatus().isLive()) throw ApiException.gone("Reservation is already " + r.getStatus().name().toLowerCase());

        boolean wasConfirmed = r.getStatus() == ReservationStatus.CONFIRMED;
        r.setStatus(ReservationStatus.CANCELLED);
        r.setCancelledAt(Instant.now());
        if (wasConfirmed) {
            payments.findByReservationIdOrderByCreatedAtDesc(r.getId()).stream()
                    .filter(p -> p.getStatus() == PaymentStatus.SUCCESS).findFirst()
                    .ifPresent(p -> { p.setStatus(PaymentStatus.REFUNDED); p.setProcessedAt(Instant.now()); });
        }
        events.log(r, "CANCELLED", wasConfirmed ? "Confirmed booking cancelled, payment refunded" : "Hold released by user");
        releaseSeat(seat, "cancellation");
        return toResponse(r, "Reservation cancelled; seat " + seat.getSeatNumber() + " released");
    }

    // ------------------------------------------------------------------ expiry

    /** Marks a HELD reservation EXPIRED and frees its seat. Caller holds both row locks. */
    void expireLocked(Reservation r, Seat seat, boolean reallocate) {
        r.setStatus(ReservationStatus.EXPIRED);
        r.setCancelledAt(Instant.now());
        events.log(r, "EXPIRED", "Hold timed out at " + r.getExpiresAt());
        if (reallocate) releaseSeat(seat, "expiry"); else seat.setStatus(SeatStatus.AVAILABLE);
    }

    @Transactional
    public int expireOne(Long reservationId) {
        Reservation r = reservations.lockById(reservationId).orElse(null);
        if (r == null || !r.isExpired(Instant.now())) return 0;
        Seat seat = seats.lockById(r.getSeat().getId()).orElseThrow();
        expireLocked(r, seat, true);
        return 1;
    }

    // ------------------------------------------------------------------ seat reallocation

    /**
     * A seat just became free. If someone is waiting in the queue, hand the seat straight to
     * them (a new HOLD, source = WAITING_QUEUE); otherwise put it back on the open market.
     * The seat row is already locked by the caller, so no direct reserve() can sneak in between.
     */
    void releaseSeat(Seat seat, String reason) {
        // Flush the terminal status of the old reservation FIRST. Hibernate otherwise orders
        // INSERTs before UPDATEs at flush time, and the new hold would trip the live-seat unique index.
        reservations.flush();
        if (props.queue().enabled()) {
            var headId = queue.lockHeadId();
            if (headId.isPresent()) {
                WaitingQueueEntry entry = queue.findById(headId.get()).orElseThrow();
                Reservation handed = createHold(entry.getUser(), seat, "WAITING_QUEUE");
                entry.setStatus(QueueStatus.ASSIGNED);
                entry.setAssignedAt(Instant.now());
                entry.setReservation(handed);
                events.log(handed, "QUEUE_ASSIGNED", "Seat freed by " + reason + " and handed to queue position 1 (user " + entry.getUser().getId() + ")");
                log.info("Seat {} freed by {} -> reassigned to waiting user {} (reservation {})", seat.getSeatNumber(), reason, entry.getUser().getId(), handed.getId());
                return;
            }
        }
        seat.setStatus(SeatStatus.AVAILABLE);
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public ReservationResponse get(Long id) {
        Reservation r = reservations.findDetailed(id).orElseThrow(() -> ApiException.notFound("Reservation " + id + " not found"));
        return toResponse(r, messageFor(r));
    }

    @Transactional(readOnly = true)
    public List<ReservationResponse> historyForUser(Long userId) {
        users.findById(userId).orElseThrow(() -> ApiException.notFound("User " + userId + " not found"));
        return reservations.findHistoryForUser(userId).stream().map(r -> toResponse(r, null)).toList();
    }

    @Transactional(readOnly = true)
    public List<EventResponse> eventsForReservation(Long id) {
        reservations.findById(id).orElseThrow(() -> ApiException.notFound("Reservation " + id + " not found"));
        return eventRepo.findByReservationIdOrderByCreatedAtAsc(id).stream().map(EventResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public List<EventResponse> eventsForUser(Long userId) {
        users.findById(userId).orElseThrow(() -> ApiException.notFound("User " + userId + " not found"));
        return eventRepo.findTop100ByUserIdOrderByCreatedAtDesc(userId).stream().map(EventResponse::of).toList();
    }

    ReservationResponse toResponse(Reservation r, String message) {
        Payment latest = payments.findByReservationIdOrderByCreatedAtDesc(r.getId()).stream().findFirst().orElse(null);
        return toResponse(r, latest, message);
    }
    ReservationResponse toResponse(Reservation r, Payment p, String message) { return ReservationResponse.of(r, p, message); }

    private String messageFor(Reservation r) {
        return switch (r.getStatus()) {
            case HELD -> "Seat held - confirm before it expires";
            case PAYMENT_PENDING -> "Payment delayed at the gateway; check back shortly";
            case CONFIRMED -> "Booking confirmed";
            case CANCELLED -> "Cancelled";
            case EXPIRED -> "Hold expired";
            case PAYMENT_FAILED -> "Payment failed; seat released";
        };
    }
}
