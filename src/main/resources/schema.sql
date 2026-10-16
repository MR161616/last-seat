-- ============================================================
--  The Last Seat - PostgreSQL schema
--  Idempotent: safe to run on every start (IF NOT EXISTS)
--  Open this file in DBeaver (SQL Editor) to create the schema
--  manually, or let Spring Boot run it at startup.
-- ============================================================

CREATE TABLE IF NOT EXISTS users (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(120)  NOT NULL,
    email       VARCHAR(200)  NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS seats (
    id          BIGSERIAL PRIMARY KEY,
    seat_number VARCHAR(10)   NOT NULL UNIQUE,     -- e.g. "A-03-07"
    section     VARCHAR(5)    NOT NULL,
    row_no      INT           NOT NULL,
    seat_no     INT           NOT NULL,
    price       NUMERIC(10,2) NOT NULL,
    status      VARCHAR(20)   NOT NULL DEFAULT 'AVAILABLE',  -- AVAILABLE | HELD | BOOKED
    version     BIGINT        NOT NULL DEFAULT 0,            -- optimistic lock counter
    CONSTRAINT seats_status_chk CHECK (status IN ('AVAILABLE','HELD','BOOKED'))
);
CREATE INDEX IF NOT EXISTS idx_seats_status ON seats(status);

CREATE TABLE IF NOT EXISTS reservations (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT       NOT NULL REFERENCES users(id),
    seat_id       BIGINT       NOT NULL REFERENCES seats(id),
    status        VARCHAR(20)  NOT NULL,
    -- HELD | PAYMENT_PENDING | CONFIRMED | CANCELLED | EXPIRED | PAYMENT_FAILED
    source        VARCHAR(20)  NOT NULL DEFAULT 'DIRECT',   -- DIRECT | WAITING_QUEUE
    reserved_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ  NOT NULL,
    confirmed_at  TIMESTAMPTZ,
    cancelled_at  TIMESTAMPTZ,
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT reservations_status_chk CHECK (status IN
        ('HELD','PAYMENT_PENDING','CONFIRMED','CANCELLED','EXPIRED','PAYMENT_FAILED'))
);
-- THE hard invariant: a seat can have at most ONE live reservation.
-- Even if application locking is bypassed, PostgreSQL rejects the second row.
CREATE UNIQUE INDEX IF NOT EXISTS uq_reservations_live_seat
    ON reservations(seat_id)
    WHERE status IN ('HELD','PAYMENT_PENDING','CONFIRMED');
CREATE INDEX IF NOT EXISTS idx_reservations_user    ON reservations(user_id, reserved_at DESC);
CREATE INDEX IF NOT EXISTS idx_reservations_expiry  ON reservations(expires_at) WHERE status = 'HELD';

CREATE TABLE IF NOT EXISTS payments (
    id              BIGSERIAL PRIMARY KEY,
    reservation_id  BIGINT        NOT NULL REFERENCES reservations(id),
    amount          NUMERIC(10,2) NOT NULL,
    mode            VARCHAR(20)   NOT NULL,   -- SUCCESS | FAIL | DELAYED | RANDOM (requested simulation)
    status          VARCHAR(20)   NOT NULL,   -- PENDING | SUCCESS | FAILED | REFUNDED
    settle_at       TIMESTAMPTZ,              -- for DELAYED payments: when the gateway "responds"
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ,
    CONSTRAINT payments_status_chk CHECK (status IN ('PENDING','SUCCESS','FAILED','REFUNDED'))
);
CREATE INDEX IF NOT EXISTS idx_payments_reservation ON payments(reservation_id);
CREATE INDEX IF NOT EXISTS idx_payments_pending     ON payments(settle_at) WHERE status = 'PENDING';

CREATE TABLE IF NOT EXISTS waiting_queue (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users(id),
    status          VARCHAR(20)  NOT NULL DEFAULT 'WAITING',  -- WAITING | ASSIGNED | LEFT
    joined_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    assigned_at     TIMESTAMPTZ,
    reservation_id  BIGINT       REFERENCES reservations(id),
    CONSTRAINT waiting_queue_status_chk CHECK (status IN ('WAITING','ASSIGNED','LEFT'))
);
-- a user can be in the queue only once at a time
CREATE UNIQUE INDEX IF NOT EXISTS uq_waiting_queue_user_waiting
    ON waiting_queue(user_id) WHERE status = 'WAITING';
CREATE INDEX IF NOT EXISTS idx_waiting_queue_order ON waiting_queue(joined_at, id) WHERE status = 'WAITING';

CREATE TABLE IF NOT EXISTS booking_events (
    id              BIGSERIAL PRIMARY KEY,
    reservation_id  BIGINT       REFERENCES reservations(id),
    seat_id         BIGINT       REFERENCES seats(id),
    user_id         BIGINT       REFERENCES users(id),
    event_type      VARCHAR(40)  NOT NULL,
    details         VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_events_reservation ON booking_events(reservation_id, created_at);
CREATE INDEX IF NOT EXISTS idx_events_user        ON booking_events(user_id, created_at DESC);
