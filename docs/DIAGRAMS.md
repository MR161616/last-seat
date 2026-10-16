# Diagrams

Both diagrams are written in [Mermaid](https://mermaid.js.org) and render directly on GitHub,
GitLab, VS Code (Markdown Preview Mermaid extension) and https://mermaid.live.

## 1. Entity-Relationship Diagram

```mermaid
erDiagram
    USERS ||--o{ RESERVATIONS : makes
    USERS ||--o{ WAITING_QUEUE : waits_in
    USERS ||--o{ BOOKING_EVENTS : triggers
    SEATS ||--o{ RESERVATIONS : "is reserved by (≤1 live)"
    SEATS ||--o{ BOOKING_EVENTS : about
    RESERVATIONS ||--o{ PAYMENTS : paid_by
    RESERVATIONS ||--o{ BOOKING_EVENTS : logs
    RESERVATIONS o|--o| WAITING_QUEUE : "handed to"

    USERS {
        bigserial id PK
        varchar name
        varchar email UK
        timestamptz created_at
    }
    SEATS {
        bigserial id PK
        varchar seat_number UK "A-03-07"
        varchar section
        int row_no
        int seat_no
        numeric price
        varchar status "AVAILABLE | HELD | BOOKED"
        bigint version "optimistic lock"
    }
    RESERVATIONS {
        bigserial id PK
        bigint user_id FK
        bigint seat_id FK "partial UNIQUE where status live"
        varchar status "HELD | PAYMENT_PENDING | CONFIRMED | CANCELLED | EXPIRED | PAYMENT_FAILED"
        varchar source "DIRECT | WAITING_QUEUE"
        timestamptz reserved_at
        timestamptz expires_at
        timestamptz confirmed_at
        timestamptz cancelled_at
        bigint version
    }
    PAYMENTS {
        bigserial id PK
        bigint reservation_id FK
        numeric amount
        varchar mode "SUCCESS | FAIL | DELAYED | RANDOM"
        varchar status "PENDING | SUCCESS | FAILED | REFUNDED"
        timestamptz settle_at "for DELAYED"
        timestamptz created_at
        timestamptz processed_at
    }
    WAITING_QUEUE {
        bigserial id PK
        bigint user_id FK "partial UNIQUE where WAITING"
        varchar status "WAITING | ASSIGNED | LEFT"
        timestamptz joined_at "FIFO order"
        timestamptz assigned_at
        bigint reservation_id FK
    }
    BOOKING_EVENTS {
        bigserial id PK
        bigint reservation_id FK
        bigint seat_id FK
        bigint user_id FK
        varchar event_type
        varchar details
        timestamptz created_at
    }
```

Key constraints that enforce correctness at the database layer:

| Constraint | Purpose |
|---|---|
| `uq_reservations_live_seat` – `UNIQUE (seat_id) WHERE status IN ('HELD','PAYMENT_PENDING','CONFIRMED')` | **At most one live reservation per seat, ever.** The last line of defence against double booking. |
| `uq_waiting_queue_user_waiting` – `UNIQUE (user_id) WHERE status = 'WAITING'` | A user cannot occupy two places in the queue. |
| `seats.version`, `reservations.version` | JPA optimistic locking; a stale write fails instead of silently overwriting. |
| `idx_reservations_expiry` (partial, on `expires_at WHERE status='HELD'`) | The expiry sweep touches only live holds. |

## 2. Booking workflow

### 2a. Reservation state machine

```mermaid
stateDiagram-v2
    [*] --> HELD : POST /reservations<br/>(seat row locked, seat → HELD)
    HELD --> CONFIRMED : confirm, payment SUCCESS<br/>(seat → BOOKED)
    HELD --> PAYMENT_FAILED : confirm, payment FAIL<br/>(seat released)
    HELD --> PAYMENT_PENDING : confirm, gateway DELAYED<br/>(hold extended)
    HELD --> EXPIRED : expires_at passes<br/>(sweeper or lazy check, seat released)
    HELD --> CANCELLED : POST /cancel<br/>(seat released)
    PAYMENT_PENDING --> CONFIRMED : settlement job: SUCCESS
    PAYMENT_PENDING --> PAYMENT_FAILED : settlement job: FAILED
    PAYMENT_PENDING --> CANCELLED : POST /cancel<br/>(late success auto-refunded)
    CONFIRMED --> CANCELLED : POST /cancel<br/>(payment REFUNDED, seat released)
    CONFIRMED --> [*]
    CANCELLED --> [*]
    EXPIRED --> [*]
    PAYMENT_FAILED --> [*]
```

### 2b. Concurrent reserve: two students, one seat

```mermaid
sequenceDiagram
    autonumber
    participant A as Student A
    participant B as Student B
    participant API as Spring Boot<br/>BookingService
    participant PG as PostgreSQL

    par both click seat A-01-01 at 18:00:00.000
        A->>API: POST /api/reservations {seat 1}
        B->>API: POST /api/reservations {seat 1}
    end
    API->>PG: BEGIN; SELECT * FROM seats WHERE id=1 FOR UPDATE  (txn A)
    API->>PG: BEGIN; SELECT * FROM seats WHERE id=1 FOR UPDATE  (txn B)
    Note over PG: txn B blocks – row is locked by txn A
    PG-->>API: seat.status = AVAILABLE (txn A)
    API->>PG: UPDATE seats SET status='HELD', version=version+1 WHERE id=1
    API->>PG: INSERT INTO reservations (user A, seat 1, HELD, expires_at=now()+120s)
    Note over PG: partial unique index uq_reservations_live_seat passes (first live row)
    API->>PG: COMMIT (txn A)
    API-->>A: 201 Created  {status: HELD, secondsRemaining: 120}
    Note over PG: lock released → txn B wakes up and re-reads the row
    PG-->>API: seat.status = HELD (txn B)
    API->>PG: ROLLBACK (txn B)
    API-->>B: 409 Conflict  "Seat A-01-01 is already held"
```

### 2c. Seat release and reallocation (expiry / cancel / failed payment)

```mermaid
flowchart TD
    T([Trigger: hold expired<br/>OR user cancelled<br/>OR payment failed]) --> L[Lock reservation row<br/>then seat row<br/><i>FOR UPDATE</i>]
    L --> S[Set reservation to terminal state<br/>EXPIRED / CANCELLED / PAYMENT_FAILED<br/>flush UPDATE]
    S --> Q{Anyone WAITING<br/>in waiting_queue?<br/><i>SELECT … FOR UPDATE SKIP LOCKED LIMIT 1</i>}
    Q -- yes --> H[Create new HELD reservation for queue head<br/>source = WAITING_QUEUE<br/>seat stays HELD]
    H --> M[Mark queue entry ASSIGNED<br/>link reservation_id]
    M --> E[Log QUEUE_ASSIGNED event]
    Q -- no --> A[seat.status = AVAILABLE]
    A --> E2[Log event]
    E --> C([COMMIT – all or nothing])
    E2 --> C
```

### 2d. End-to-end user journey

```mermaid
flowchart LR
    U[Student] -->|POST /api/users| R[Registered]
    R -->|GET /api/seats| V[View seat map]
    V -->|seat AVAILABLE?| D{ }
    D -- yes --> RS[POST /api/reservations<br/>201 HELD, 120 s timer]
    D -- no, atCapacity --> WQ[POST /api/queue/join<br/>position N]
    WQ -.->|seat freed → auto hold| RS
    RS -->|POST /confirm<br/>paymentMode| P{Payment}
    P -- SUCCESS --> CF[CONFIRMED ✓]
    P -- FAIL --> PF[PAYMENT_FAILED<br/>seat reallocated]
    P -- DELAYED --> PP[PAYMENT_PENDING<br/>settled by job in ~15 s] --> P
    RS -->|timer runs out| EX[EXPIRED<br/>seat reallocated]
    RS -->|POST /cancel| CA[CANCELLED<br/>seat reallocated]
    CF -->|POST /cancel| CA
    CF & PF & EX & CA -->|GET /api/users/{id}/bookings| HI[Booking history]
```
