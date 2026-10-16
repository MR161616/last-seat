# Concurrency handling & how the system scales

## 1. The problem in one sentence

10,000 students, 500 seats, one instant. The only invariant that truly matters is:

> **A seat is never booked more than once.**

Everything else (fairness, latency, nice error messages) is secondary. So the design puts that
invariant in the place that cannot be bypassed – the database – and layers application-level
locking on top for clean behaviour under contention.

## 2. Three layers of protection

### Layer 1 – Row-level pessimistic locking (`SELECT … FOR UPDATE`)

`BookingService.reserve()` is one transaction. The first thing it does is

```java
Seat seat = seats.lockById(seatId);   // @Lock(PESSIMISTIC_WRITE) → SELECT ... FOR UPDATE
```

PostgreSQL grants the row lock to exactly one transaction. Every other transaction that asks
for the same seat **blocks on that line** until the winner commits or rolls back, then
re-reads the row and sees `status = HELD`. They return `409 Conflict`; they never see a stale
`AVAILABLE`. There is no "check-then-act" window because the check and the act happen while
the row is exclusively ours.

This is "lock the seat, not the venue": requests for *different* seats never wait on each other,
so throughput stays high; only the (unavoidable) contention for *one* seat is serialised.

`confirm()` and `cancel()` lock the reservation row first, then the seat row. The lock order is
always **reservation → seat → waiting_queue**, which is what makes deadlocks impossible.

### Layer 2 – Database constraint (the invariant itself)

```sql
CREATE UNIQUE INDEX uq_reservations_live_seat
    ON reservations(seat_id)
    WHERE status IN ('HELD','PAYMENT_PENDING','CONFIRMED');
```

A partial unique index: among *live* reservations, `seat_id` must be unique. Historical rows
(EXPIRED, CANCELLED, PAYMENT_FAILED) are excluded, so the seat can be re-sold, but two live rows
can never coexist. If a bug, a future code path, a direct SQL edit or a second application
instance ever tries to create a second live reservation, PostgreSQL rejects it and the API
returns 409 (see `GlobalExceptionHandler#integrity`). The invariant holds **even if every line
of Java were wrong**.

### Layer 3 – Optimistic locking (`@Version`)

`seats.version` and `reservations.version` are incremented on every update. Any transaction that
loaded a row, lost a race, and tries to write a stale copy fails with an
`OptimisticLockException` → 409. This mainly protects the background jobs and admin paths that
don't take explicit `FOR UPDATE` locks on every row they touch.

## 3. Temporary reservations that expire

A hold is just a `reservations` row with `status='HELD'` and `expires_at = now() + 120 s`
(configurable: `HOLD_SECONDS`). Two mechanisms release it:

1. **Sweeper** – `Schedulers.expireHolds()` runs every 2 s:
   ```sql
   SELECT id FROM reservations
   WHERE status='HELD' AND expires_at < now()
   ORDER BY expires_at LIMIT 200
   FOR UPDATE SKIP LOCKED
   ```
   `SKIP LOCKED` is the important part: a hold that is being confirmed *right now* is locked
   by the confirm transaction, so the sweeper skips it instead of blocking. It can never expire
   a reservation out from under a payment, and several app instances can run the sweeper
   simultaneously without processing the same row twice.

2. **Lazy expiry** – `reserve()` and `confirm()` also check `expires_at` themselves. If the
   sweeper hasn't got to a stale hold yet, the next request that touches that seat releases it
   first. Users therefore never see a seat that is "held" by a timer that already ran out, and
   a user whose hold expired 50 ms ago gets `410 Gone` when they try to pay, not a confirmed
   booking.

## 4. Payments: success, failure, delay

`PaymentGateway` is a simulator driven by `paymentMode` on the confirm request:

| mode | gateway behaviour | reservation | seat |
|---|---|---|---|
| `SUCCESS` | charges immediately | `CONFIRMED` | `BOOKED` |
| `FAIL` | declines immediately | `PAYMENT_FAILED` | released / reallocated |
| `DELAYED` | "times out"; answers after `PAYMENT_DELAY_SECONDS` | `PAYMENT_PENDING` (hold extended) | stays `HELD` |
| `RANDOM` | 70 % success / 20 % fail / 10 % delayed | one of the above | |

Delayed payments are the subtle case: the API returns `202 Accepted`, the hold's `expires_at` is
pushed past the expected settlement time so the sweeper leaves it alone, and
`Schedulers.settleDelayedPayments()` (again `FOR UPDATE SKIP LOCKED`) finishes the job. If the
user cancels while the gateway is still thinking and the payment later succeeds, the payment is
marked `REFUNDED` – the seat was already handed on, and money is never silently kept.

## 5. Repeated and concurrent requests (consistency)

| situation | behaviour |
|---|---|
| Same user double-clicks "reserve" | second call finds the user's own live reservation for that seat and returns it with `200` (not a new hold, not a 409). `k6` and `Rush.java` assert the ids match. |
| Same user double-clicks "confirm" | the row is locked; second call sees `CONFIRMED` and returns it unchanged. No second charge. |
| Same user double-clicks "cancel" | second call returns the already-cancelled reservation, `200`. |
| Two users, one seat | exactly one `201`, the other `409` (Layer 1 + 2). |
| 200 users, one seat, same millisecond | `SameSeatRace.java` – exactly one `201`, 199 × `409`. |
| Sign-up race on one e-mail | unique constraint; the loser reads the winner's row. |
| Queue join race | partial unique index on `(user_id) WHERE WAITING`. |

All reads of status (`GET /reservations/{id}`, history, queue position) are plain committed
reads – they are never allowed to block a writer.

## 6. Waiting queue (bonus)

When `available == 0` the UI (and API: `POST /api/queue/join`) lets a user enter a FIFO queue.
Reallocation is done **inside the releasing transaction** (`BookingService.releaseSeat`):

```sql
SELECT id FROM waiting_queue WHERE status='WAITING'
ORDER BY joined_at, id LIMIT 1
FOR UPDATE SKIP LOCKED
```

If a row comes back, the freed seat is turned straight into a new `HELD` reservation for that
user (source `WAITING_QUEUE`), the queue entry becomes `ASSIGNED`, and the seat **never passes
through `AVAILABLE`** – so a fast direct `reserve()` can't snipe it from the people who waited.
If the queued user ignores their hold, it expires like any other and moves to the next person.
Two seats freed at the same instant lock two *different* head rows thanks to `SKIP LOCKED`.

## 7. How the application scales

### Today (single JVM + single PostgreSQL)

* The locking is in PostgreSQL, not in JVM memory (`synchronized`, `ReentrantLock`, in-process
  maps). That is a deliberate choice: it means **you can run N copies of the app behind a load
  balancer with zero code changes** and correctness still holds, because every instance
  serialises on the same database rows.
* The hot path for one seat is a few row lookups and two writes on indexed columns; measured
  p95 under 200–300 concurrent users is well under a second on a laptop (see
  `LOAD_TEST_RESULTS.md`).
* Hikari pool (30) < Tomcat threads (200): under extreme load requests queue briefly for a
  connection rather than opening thousands of PostgreSQL sessions.

### Next steps, in the order we would do them

1. **Horizontal app scaling** – `docker compose up --scale app=4` + nginx. Already safe
   (stateless app, DB-level locks, `SKIP LOCKED` schedulers).
2. **Read replica + caching for the seat map** – `GET /api/seats` is 99 % of traffic during the
   rush and tolerates a second of staleness. Serve it from Redis (`seat:status` hash, updated on
   every state change) or a replica; keep writes on the primary.
3. **Rate limiting / virtual waiting room at the edge** – with 10 k users the real bottleneck
   is the *thundering herd* at 18:00:00. Admit users to the booking page in batches (token
   bucket in Redis or an nginx `limit_req`) so the database sees a steady few hundred
   requests/s rather than a 10 k spike.
4. **Async side effects** – confirmation e-mails, analytics, audit fan-out via
   RabbitMQ/Kafka; `booking_events` is already the natural outbox table.
5. **Partition by event** – when there are many concerts, add `event_id` to `seats` /
   `reservations` and partition on it; each concert's rush only contends with itself.
6. **Observability** – Actuator `/actuator/metrics` is enabled; add Micrometer → Prometheus,
   alert on `hikaricp.connections.pending` and on 503s from lock-timeouts.

### What we deliberately did *not* do

* No in-memory locks – they break the moment you run two instances.
* No "optimistic only" design – under a true hot-spot (200 users, 1 seat) optimistic locking
  causes 199 retries; pessimistic row locks make them wait a few ms and get a definitive answer.
* No distributed lock service (Redis `SETNX`, ZooKeeper) – PostgreSQL row locks already give
  the same guarantee with ACID semantics and no extra moving part.