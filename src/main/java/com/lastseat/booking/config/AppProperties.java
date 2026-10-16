package com.lastseat.booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Concert concert, Reservation reservation, Payment payment, Queue queue, Admin admin) {

    public record Concert(String name, int totalSeats, List<String> sections, int rowsPerSection,
                          int seatsPerRow, BigDecimal basePrice) {}

    public record Reservation(int holdSeconds, long expirySweepMs) {}

    public record Payment(int delayedSettleSeconds, double randomSuccessRate) {}

    public record Queue(boolean enabled) {}

    public record Admin(boolean resetEnabled) {}
}
