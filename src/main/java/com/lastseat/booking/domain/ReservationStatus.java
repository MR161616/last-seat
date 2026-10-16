package com.lastseat.booking.domain;

public enum ReservationStatus {
    HELD,             // temporary hold, will expire
    PAYMENT_PENDING,  // payment submitted to a slow gateway, hold extended until it settles
    CONFIRMED,        // paid, seat is BOOKED
    CANCELLED,        // user cancelled (hold or confirmed booking)
    EXPIRED,          // hold timed out
    PAYMENT_FAILED;   // gateway declined, seat released

    public boolean isLive() { return this == HELD || this == PAYMENT_PENDING || this == CONFIRMED; }
}
