package com.lastseat.booking.service;

import com.lastseat.booking.repository.PaymentRepository;
import com.lastseat.booking.repository.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * Background jobs. Both use "SELECT ... FOR UPDATE SKIP LOCKED" so that
 *  (a) several app instances can run the same job without double-processing a row, and
 *  (b) a row currently inside a user transaction is skipped, never blocked on.
 */
@Component
public class Schedulers {
    private static final Logger log = LoggerFactory.getLogger(Schedulers.class);
    private static final int BATCH = 200;

    private final ReservationRepository reservations;
    private final PaymentRepository payments;
    private final BookingService booking;
    private final TransactionTemplate tx;

    public Schedulers(ReservationRepository reservations, PaymentRepository payments, BookingService booking, TransactionTemplate tx) {
        this.reservations = reservations; this.payments = payments; this.booking = booking; this.tx = tx;
    }

    /** Releases holds whose expires_at has passed. */
    @Scheduled(fixedDelayString = "${app.reservation.expiry-sweep-ms}")
    public void expireHolds() {
        List<Long> ids = tx.execute(s -> reservations.findExpiredIdsForUpdate(Instant.now(), BATCH));
        int released = 0;
        for (Long id : ids) {
            try { released += booking.expireOne(id); }
            catch (Exception e) { log.warn("Could not expire reservation {}: {}", id, e.getMessage()); }
        }
        if (released > 0) log.info("Expiry sweep released {} hold(s)", released);
    }

    /** Settles DELAYED payments whose simulated gateway delay has elapsed. */
    @Scheduled(fixedDelay = 1000)
    public void settleDelayedPayments() {
        List<Long> ids = tx.execute(s -> payments.findDuePendingIdsForUpdate(Instant.now(), BATCH));
        for (Long id : ids) {
            try { booking.settlePayment(id); }
            catch (Exception e) { log.warn("Could not settle payment {}: {}", id, e.getMessage()); }
        }
    }
}
