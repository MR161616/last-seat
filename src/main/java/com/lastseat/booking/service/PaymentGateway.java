package com.lastseat.booking.service;

import com.lastseat.booking.config.AppProperties;
import com.lastseat.booking.domain.PaymentMode;
import com.lastseat.booking.domain.PaymentStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulated payment gateway. The caller chooses the behaviour:
 *  SUCCESS -> immediate success,  FAIL -> immediate decline,
 *  DELAYED -> gateway "times out"; the outcome arrives later via the settlement job,
 *  RANDOM  -> 70% success / 20% fail / 10% delayed (configurable).
 */
@Component
public class PaymentGateway {
    public record Outcome(PaymentStatus status, Instant settleAt) {}

    private final AppProperties props;
    public PaymentGateway(AppProperties props) { this.props = props; }

    public Outcome charge(PaymentMode mode) {
        PaymentMode effective = mode == null ? PaymentMode.SUCCESS : mode;
        if (effective == PaymentMode.RANDOM) {
            double roll = ThreadLocalRandom.current().nextDouble();
            double successRate = props.payment().randomSuccessRate();
            effective = roll < successRate ? PaymentMode.SUCCESS : roll < successRate + 0.2 ? PaymentMode.FAIL : PaymentMode.DELAYED;
        }
        return switch (effective) {
            case SUCCESS -> new Outcome(PaymentStatus.SUCCESS, null);
            case FAIL -> new Outcome(PaymentStatus.FAILED, null);
            case DELAYED -> new Outcome(PaymentStatus.PENDING, Instant.now().plusSeconds(props.payment().delayedSettleSeconds()));
            case RANDOM -> throw new IllegalStateException("unreachable");
        };
    }

    /** Called by the settlement job when a DELAYED payment finally "responds". */
    public PaymentStatus settleDelayed() {
        return ThreadLocalRandom.current().nextDouble() < 0.8 ? PaymentStatus.SUCCESS : PaymentStatus.FAILED;
    }
}
