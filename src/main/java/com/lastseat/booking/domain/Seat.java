package com.lastseat.booking.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "seats")
public class Seat {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "seat_number", nullable = false, unique = true) private String seatNumber;
    @Column(nullable = false) private String section;
    @Column(name = "row_no", nullable = false) private int rowNo;
    @Column(name = "seat_no", nullable = false) private int seatNo;
    @Column(nullable = false) private BigDecimal price;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private SeatStatus status = SeatStatus.AVAILABLE;
    /** Optimistic-lock counter: a stale update throws OptimisticLockException. */
    @Version private long version;

    protected Seat() {}
    public Seat(String seatNumber, String section, int rowNo, int seatNo, BigDecimal price) {
        this.seatNumber = seatNumber; this.section = section; this.rowNo = rowNo; this.seatNo = seatNo; this.price = price;
    }

    public Long getId() { return id; }
    public String getSeatNumber() { return seatNumber; }
    public String getSection() { return section; }
    public int getRowNo() { return rowNo; }
    public int getSeatNo() { return seatNo; }
    public BigDecimal getPrice() { return price; }
    public SeatStatus getStatus() { return status; }
    public void setStatus(SeatStatus status) { this.status = status; }
    public long getVersion() { return version; }
}
