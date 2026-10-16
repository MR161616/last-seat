package com.lastseat.booking.repository;

import com.lastseat.booking.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    List<Payment> findByReservationIdOrderByCreatedAtDesc(Long reservationId);

    @Query(value = """
            select id from payments
            where status = 'PENDING' and settle_at <= :now
            order by settle_at
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<Long> findDuePendingIdsForUpdate(@Param("now") Instant now, @Param("limit") int limit);
}
