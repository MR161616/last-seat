package com.lastseat.booking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class LastSeatApplication {
    public static void main(String[] args) {
        // All timestamps (expires_at, settle_at ...) are stored and compared in UTC, regardless of host locale.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(LastSeatApplication.class, args);
    }
}
