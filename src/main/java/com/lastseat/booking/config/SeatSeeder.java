package com.lastseat.booking.config;

import com.lastseat.booking.domain.Seat;
import com.lastseat.booking.repository.SeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Seeds the 500-seat venue on first start. Runs once; later restarts are no-ops. */
@Configuration
public class SeatSeeder {
    private static final Logger log = LoggerFactory.getLogger(SeatSeeder.class);

    @Bean
    ApplicationRunner seedSeats(SeatRepository seats, AppProperties props) {
        return args -> {
            if (seats.count() > 0) {
                log.info("Seat inventory already present ({} seats) - skipping seed", seats.count());
                return;
            }
            seats.saveAll(buildVenue(props.concert()));
            log.info("Seeded {} seats", seats.count());
        };
    }

    public static List<Seat> buildVenue(AppProperties.Concert c) {
        List<Seat> list = new ArrayList<>();
        for (int s = 0; s < c.sections().size(); s++) {
            String section = c.sections().get(s);
            // front sections cost more: A = base + 400, B = base + 300 ...
            BigDecimal price = c.basePrice().add(BigDecimal.valueOf((c.sections().size() - 1 - s) * 100L));
            for (int r = 1; r <= c.rowsPerSection(); r++) {
                for (int n = 1; n <= c.seatsPerRow(); n++) {
                    list.add(new Seat(String.format("%s-%02d-%02d", section, r, n), section, r, n, price));
                }
            }
        }
        if (list.size() != c.totalSeats()) {
            throw new IllegalStateException("Venue layout produces " + list.size() + " seats, expected " + c.totalSeats());
        }
        return list;
    }
}
