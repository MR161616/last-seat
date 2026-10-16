package com.lastseat.booking.web;

import com.lastseat.booking.domain.SeatStatus;
import com.lastseat.booking.dto.Dtos.SeatResponse;
import com.lastseat.booking.dto.Dtos.SeatSummary;
import com.lastseat.booking.service.SeatService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/seats")
public class SeatController {
    private final SeatService seats;
    public SeatController(SeatService seats) { this.seats = seats; }

    /** All seats, optionally filtered: /api/seats?status=AVAILABLE */
    @GetMapping
    public List<SeatResponse> list(@RequestParam(required = false) SeatStatus status) { return seats.list(status); }

    @GetMapping("/summary")
    public SeatSummary summary() { return seats.summary(); }

    @GetMapping("/{id}")
    public SeatResponse get(@PathVariable Long id) { return seats.get(id); }
}
