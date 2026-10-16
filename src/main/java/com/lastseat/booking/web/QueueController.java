package com.lastseat.booking.web;

import com.lastseat.booking.dto.Dtos.*;
import com.lastseat.booking.service.WaitingQueueService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/queue")
public class QueueController {
    private final WaitingQueueService queue;
    public QueueController(WaitingQueueService queue) { this.queue = queue; }

    /** Join the waiting queue. Only allowed when no seat is AVAILABLE (409 otherwise). */
    @PostMapping("/join")
    @ResponseStatus(HttpStatus.CREATED)
    public QueueEntryResponse join(@Valid @RequestBody QueueJoinRequest req) { return queue.join(req.userId()); }

    @DeleteMapping("/{userId}")
    public QueueEntryResponse leave(@PathVariable Long userId) { return queue.leave(userId); }

    /** Position in the queue, or the reservation that was handed to this user. */
    @GetMapping("/{userId}")
    public QueueEntryResponse status(@PathVariable Long userId) { return queue.status(userId); }

    @GetMapping
    public QueueSummary summary() { return queue.summary(); }
}
