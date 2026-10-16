# Load-test results

All numbers below were measured on **2026-10-15** against the code in this repository.
Raw output is in `loadtest/results/`.

**Environment (deliberately modest):** one Linux container, 2 vCPU (Intel Xeon 2.1 GHz),
7 GB RAM, running the Spring Boot app (default settings: Hikari pool 30, Tomcat 200 threads),
PostgreSQL 16 **and** the load generator all on the same box. Production hardware with a
dedicated database server will do considerably better; the point of these runs is behaviour
under contention, not peak throughput.

## 1. Same-seat race — `loadtest/SameSeatRace.java`

200 users, one seat, all requests released at the same instant by a `CountDownLatch`. Repeated
for three different seats.

```
round 1 seat 1: 200 simultaneous reserves in 2254 ms -> {409: 199, 201: 1} | p50=1565ms p95=2068ms max=2213ms
round 2 seat 2: 200 simultaneous reserves in 1187 ms -> {409: 199, 201: 1} | p50=624ms  p95=962ms  max=1146ms
round 3 seat 3: 200 simultaneous reserves in 1004 ms -> {409: 199, 201: 1} | p50=555ms  p95=800ms  max=926ms
db verify: {'seatStatusMismatches': 0, 'doubleBookedSeats': 0, 'consistent': True}
```

**Exactly one `201` per round, 199 × `409`, every time.** Round 1 is slower because the JVM
is still warming up (JIT, Hikari filling the pool); rounds 2–3 show the steady state: 200
contending transactions serialised on one `SELECT … FOR UPDATE` row lock drain in about a second.

## 2. The 6 PM rush — `loadtest/booking-rush.js` (k6)

Scenario per virtual user, looped: register → pick a seat (70 % from the 50 "hot" front-row
seats) → reserve → **immediately re-send the same reserve** (double-click) → think 0–1.5 s →
15 % abandon (hold expires), otherwise pay with SUCCESS 60 % / FAIL 15 % / DELAYED 10 % → 10 % of
successful payers cancel afterwards. If the venue is at capacity the user joins the waiting queue.

| | 200 VUs, 45 s plateau | 500 VUs, 60 s plateau |
|---|---|---|
| HTTP requests | 25 362 | 38 416 |
| Throughput | **421 req/s** | **504 req/s** |
| Latency median / p90 / p95 / max | 173 / 674 / 803 / 2005 ms | 726 / 1484 / 1668 / 3603 ms |
| `http_req_failed` (5xx, timeouts) | **0.00 %** | **0.00 %** |
| Reserve → `201` new hold | 628 | 623 |
| Reserve → `409` seat taken | 7 582 | 11 970 |
| Reserve → `200` repeat (idempotent) | 39 | 24 |
| "repeat reserve is idempotent" check | 100 % pass (same reservation id) | 100 % pass |
| Confirm → `200` paid | 412 | 378 |
| Confirm → `402` payment declined | 93 | 78 |
| Final inventory | 404 booked · 85 held · 11 free | 385 booked · 111 held · 4 free |
| **Double-booked seats (DB check)** | **0** | **0** |
| **Seat/reservation status mismatches** | **0** | **0** |

Reading the table:

* **Correctness held under both loads**: `POST /api/admin/verify` (a SQL `GROUP BY seat_id HAVING
  count(*) > 1` over live reservations, plus a seat-status cross-check) found nothing, and
  every "double-click" was answered with the *same* reservation rather than a second one.
* The huge `409` counts are the expected outcome of 200–500 people hammering 50 hot seats; the
  server answers them cheaply (a row lock wait of a few ms, then a short read) instead of
  letting them through.
* The 201 count is bounded by the inventory: ~620 holds were created over the run because
  holds that expire or fail are re-sold, so slightly more than 500 holds exist over time.
* No 5xx at all. The only threshold that tripped was my own p95 < 1.5 s target at 500 VUs
  (measured 1.67 s) — on a 2-vCPU box shared with PostgreSQL and the load generator, where the
  CPU was saturated. At 200 VUs the p95 was 0.8 s.

Final database state after the 500-VU run, from DBeaver / psql:

```
 reservations.status | count        payments.status | count
---------------------+-------       ----------------+-------
 CANCELLED           |    34        FAILED          |    93
 CONFIRMED           |   385        PENDING         |     1
 HELD                |   110        REFUNDED        |    34
 PAYMENT_FAILED      |    93        SUCCESS         |   385
 PAYMENT_PENDING     |     1
```

Every cancelled confirmed booking has a matching `REFUNDED` payment (34 = 34), every
`CONFIRMED` reservation a `SUCCESS` payment (385 = 385).

## 3. Expiry and waiting-queue behaviour (functional run, `HOLD_SECONDS=8`)

Scripted scenario run before the load tests (output abridged):

```
500 users hold all 500 seats; first 10 pay            -> summary: available=0 held=490 booked=10 atCapacity=true
user 501 tries to reserve seat 1                      -> 409 "Seat A-01-01 is already booked"
users 501, 502, 503 join the queue                    -> positions [1, 2, 3]; duplicate join -> "already in the queue"
user 1 cancels their confirmed booking                -> seat A-01-01 handed to user 501:
                                                         queue status ASSIGNED, reservation HELD, source WAITING_QUEUE
                                                         user 502 is now position 1; seat stays HELD (never AVAILABLE)
12 s later (holds expired)                            -> summary: available=491 held=0 booked=9
                                                         users 502 and 503: ASSIGNED (got the first two expired seats)
confirm on an expired hold                            -> 410 "Reservation is expired and can no longer be confirmed"
POST /api/admin/verify                                -> doubleBookedSeats=0, seatStatusMismatches=0
```

## 4. How to reproduce

```bash
docker compose up --build -d            # app + PostgreSQL
java loadtest/SameSeatRace.java 200
k6 run -e VUS=200 -e DURATION=45s loadtest/booking-rush.js
k6 run -e VUS=500 -e DURATION=60s loadtest/booking-rush.js
# or without k6 installed:
docker compose --profile loadtest run --rm k6
java loadtest/Rush.java 300 10
```