package com.lastseat.booking.service;

import com.lastseat.booking.domain.QueueStatus;
import com.lastseat.booking.domain.SeatStatus;
import com.lastseat.booking.dto.Dtos.SeatResponse;
import com.lastseat.booking.dto.Dtos.SeatSummary;
import com.lastseat.booking.repository.SeatRepository;
import com.lastseat.booking.repository.WaitingQueueRepository;
import com.lastseat.booking.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SeatService {
    private final SeatRepository seats;
    private final WaitingQueueRepository queue;
    public SeatService(SeatRepository seats, WaitingQueueRepository queue) { this.seats = seats; this.queue = queue; }

    @Transactional(readOnly = true)
    public List<SeatResponse> list(SeatStatus status) {
        var rows = status == null ? seats.findAllByOrderBySectionAscRowNoAscSeatNoAsc() : seats.findByStatusOrderBySectionAscRowNoAscSeatNoAsc(status);
        return rows.stream().map(SeatResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public SeatResponse get(Long id) {
        return seats.findById(id).map(SeatResponse::of).orElseThrow(() -> ApiException.notFound("Seat " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public SeatSummary summary() {
        long available = seats.countByStatus(SeatStatus.AVAILABLE);
        long held = seats.countByStatus(SeatStatus.HELD);
        long booked = seats.countByStatus(SeatStatus.BOOKED);
        return new SeatSummary(available + held + booked, available, held, booked, queue.countByStatus(QueueStatus.WAITING), available == 0);
    }
}
