package com.lastseat.booking.web;

import com.lastseat.booking.dto.Dtos.*;
import com.lastseat.booking.service.BookingService;
import com.lastseat.booking.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService users;
    private final BookingService booking;
    public UserController(UserService users, BookingService booking) { this.users = users; this.booking = booking; }

    /** Register (or look up) a student by e-mail. Idempotent. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody CreateUserRequest req) {
        return UserResponse.of(users.getOrCreate(req.name(), req.email()));
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable Long id) { return UserResponse.of(users.get(id)); }

    /** Booking history: every reservation the user ever made, newest first. */
    @GetMapping("/{id}/bookings")
    public List<ReservationResponse> bookings(@PathVariable Long id) { return booking.historyForUser(id); }

    /** Audit trail across all of the user's reservations and queue activity. */
    @GetMapping("/{id}/events")
    public List<EventResponse> events(@PathVariable Long id) { return booking.eventsForUser(id); }
}
