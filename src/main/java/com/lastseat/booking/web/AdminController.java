package com.lastseat.booking.web;

import com.lastseat.booking.config.AppProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Dev / load-test helper. Disable in production with ADMIN_RESET_ENABLED=false. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final JdbcTemplate jdbc;
    private final AppProperties props;
    public AdminController(JdbcTemplate jdbc, AppProperties props) { this.jdbc = jdbc; this.props = props; }

    /** Clears all bookings and puts every seat back to AVAILABLE. Users are kept. */
    @PostMapping("/reset")
    @Transactional
    public Map<String, Object> reset() {
        if (!props.admin().resetEnabled()) throw ApiException.forbidden("Reset is disabled");
        jdbc.update("TRUNCATE booking_events, payments, waiting_queue, reservations RESTART IDENTITY");
        int seats = jdbc.update("UPDATE seats SET status = 'AVAILABLE', version = version + 1");
        return Map.of("reset", true, "seatsReset", seats);
    }

    /** Database-level consistency check: is any seat referenced by more than one live reservation? */
    @PostMapping("/verify")
    public Map<String, Object> verify() {
        Integer dup = jdbc.queryForObject("""
                select count(*) from (select seat_id from reservations
                where status in ('HELD','PAYMENT_PENDING','CONFIRMED') group by seat_id having count(*) > 1) d""", Integer.class);
        Integer mismatch = jdbc.queryForObject("""
                select count(*) from seats s
                where (s.status = 'AVAILABLE' and exists (select 1 from reservations r where r.seat_id = s.id and r.status in ('HELD','PAYMENT_PENDING','CONFIRMED')))
                   or (s.status in ('HELD','BOOKED') and not exists (select 1 from reservations r where r.seat_id = s.id and r.status in ('HELD','PAYMENT_PENDING','CONFIRMED')))""", Integer.class);
        return Map.of("doubleBookedSeats", dup, "seatStatusMismatches", mismatch, "consistent", dup == 0 && mismatch == 0);
    }
}
