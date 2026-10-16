package com.lastseat.booking.web;

import com.lastseat.booking.dto.Dtos.*;
import com.lastseat.booking.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {
    private final BookingService booking;
    public ReservationController(BookingService booking) { this.booking = booking; }

    /**
     * Temporarily reserve a seat.
     * 201 new hold | 200 you already held it (repeat request) | 409 somebody else has it
     */
    @PostMapping
    public ResponseEntity<ReservationResponse> reserve(@Valid @RequestBody ReserveRequest req) {
        var result = booking.reserve(req.userId(), req.seatId());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.reservation());
    }

    /** Reservation status (HELD / PAYMENT_PENDING / CONFIRMED / ...), with seconds remaining. */
    @GetMapping("/{id}")
    public ReservationResponse get(@PathVariable Long id) { return booking.get(id); }

    /** Pay and confirm. paymentMode: SUCCESS (default) | FAIL | DELAYED | RANDOM. 202 if the gateway is slow. */
    @PostMapping("/{id}/confirm")
    public ResponseEntity<ReservationResponse> confirm(@PathVariable Long id, @Valid @RequestBody ConfirmRequest req) {
        ReservationResponse r = booking.confirm(id, req.userId(), req.paymentMode());
        HttpStatus code = switch (r.status()) {
            case PAYMENT_PENDING -> HttpStatus.ACCEPTED;
            case PAYMENT_FAILED -> HttpStatus.PAYMENT_REQUIRED;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(code).body(r);
    }

    /** Cancel a hold or a confirmed booking; the seat is reallocated immediately. */
    @PostMapping("/{id}/cancel")
    public ReservationResponse cancel(@PathVariable Long id, @Valid @RequestBody CancelRequest req) {
        return booking.cancel(id, req.userId());
    }

    /** Full event timeline of one reservation. */
    @GetMapping("/{id}/history")
    public List<EventResponse> history(@PathVariable Long id) { return booking.eventsForReservation(id); }
}
