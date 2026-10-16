package com.lastseat.booking.service;

import com.lastseat.booking.config.AppProperties;
import com.lastseat.booking.domain.*;
import com.lastseat.booking.dto.Dtos.*;
import com.lastseat.booking.repository.SeatRepository;
import com.lastseat.booking.repository.WaitingQueueRepository;
import com.lastseat.booking.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Waiting queue (bonus). When every seat is held or booked, users join a FIFO queue.
 * The moment a seat is released (expiry, cancellation, failed payment) BookingService.releaseSeat
 * hands it to the head of the queue as a fresh temporary hold, which the user then confirms
 * like any other reservation - or lets expire, in which case the seat moves to the next person.
 */
@Service
public class WaitingQueueService {
    private final WaitingQueueRepository queue;
    private final SeatRepository seats;
    private final UserService users;
    private final BookingService booking;
    private final EventLogger events;
    private final AppProperties props;
    private final JdbcTemplate jdbc;

    public WaitingQueueService(WaitingQueueRepository queue, SeatRepository seats, UserService users,
                               BookingService booking, EventLogger events, AppProperties props, JdbcTemplate jdbc) {
        this.queue = queue; this.seats = seats; this.users = users; this.booking = booking; this.events = events; this.props = props; this.jdbc = jdbc;
    }

    @Transactional
    public QueueEntryResponse join(Long userId) {
        if (!props.queue().enabled()) throw ApiException.badRequest("Waiting queue is disabled");
        User user = users.get(userId);
        var existing = queue.findFirstByUserIdAndStatus(userId, QueueStatus.WAITING);
        if (existing.isPresent()) return toResponse(existing.get(), "You are already in the queue");

        long available = seats.countByStatus(SeatStatus.AVAILABLE);
        if (available > 0) {
            throw ApiException.conflict(available + " seat(s) are still available - reserve one directly instead of queueing");
        }
        // race-free insert: the partial unique index (user_id WHERE status='WAITING') is the target
        int inserted = jdbc.update("INSERT INTO waiting_queue(user_id) VALUES (?) ON CONFLICT (user_id) WHERE status = 'WAITING' DO NOTHING", userId);
        WaitingQueueEntry e = queue.findFirstByUserIdAndStatus(userId, QueueStatus.WAITING).orElseThrow();
        if (inserted == 1) events.log(null, null, userId, "QUEUE_JOINED", "Joined waiting queue (entry " + e.getId() + ")");
        return toResponse(e, inserted == 1 ? "Added to the waiting queue" : "You are already in the queue");
    }

    @Transactional
    public QueueEntryResponse leave(Long userId) {
        WaitingQueueEntry e = queue.findFirstByUserIdAndStatus(userId, QueueStatus.WAITING)
                .orElseThrow(() -> ApiException.notFound("User " + userId + " is not waiting in the queue"));
        e.setStatus(QueueStatus.LEFT);
        events.log(null, null, userId, "QUEUE_LEFT", "Left waiting queue");
        return toResponse(e, "Removed from the waiting queue");
    }

    @Transactional(readOnly = true)
    public QueueEntryResponse status(Long userId) {
        WaitingQueueEntry e = queue.findFirstByUserIdOrderByJoinedAtDesc(userId)
                .orElseThrow(() -> ApiException.notFound("User " + userId + " has never joined the queue"));
        return toResponse(e, switch (e.getStatus()) {
            case WAITING -> "Waiting for a seat to free up";
            case ASSIGNED -> "A seat was assigned to you - confirm it before the hold expires";
            case LEFT -> "You left the queue";
        });
    }

    @Transactional(readOnly = true)
    public QueueSummary summary() {
        List<WaitingQueueEntry> waiting = queue.findWaitingInOrder();
        boolean atCapacity = seats.countByStatus(SeatStatus.AVAILABLE) == 0;
        List<Map<String, Object>> head = waiting.stream().limit(10)
                .map(e -> Map.<String, Object>of("position", waiting.indexOf(e) + 1, "userId", e.getUser().getId(),
                        "name", e.getUser().getName(), "joinedAt", e.getJoinedAt()))
                .toList();
        return new QueueSummary(waiting.size(), atCapacity, head);
    }

    private QueueEntryResponse toResponse(WaitingQueueEntry e, String message) {
        Long position = e.getStatus() == QueueStatus.WAITING ? queue.positionOf(e.getId()) : null;
        ReservationResponse assigned = e.getReservation() == null ? null : booking.toResponse(e.getReservation(), null);
        return new QueueEntryResponse(e.getId(), e.getUser().getId(), e.getStatus(), position,
                position == null ? 0 : position - 1, e.getJoinedAt(), e.getAssignedAt(), assigned, message);
    }
}
