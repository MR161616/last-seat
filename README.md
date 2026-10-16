# The Last Seat 🎟 — concurrent concert ticket booking

> **CC-VIT 26-27 · Backend Development task.** 500 seats, 10 000 students, one 6 PM rush.
> No seat may ever be booked twice.

| Layer | Tech |
|---|---|
| Frontend | HTML · CSS · vanilla JavaScript (served by the backend at `/`) |
| Backend | Java 21 · Spring Boot 3.3 (Web, Data JPA, Validation, Actuator) |
| Database | PostgreSQL 16 (managed/inspected with DBeaver) |
| Packaging | Docker + docker-compose |
| Load testing | k6 (`loadtest/booking-rush.js`) + dependency-free Java programs (`loadtest/*.java`) |

## Contents

```
last-seat/
├── src/main/java/com/lastseat/booking/
│   ├── config/        AppProperties, SeatSeeder (500 seats), CORS
│   ├── domain/        JPA entities + enums (Seat, Reservation, Payment, WaitingQueueEntry, BookingEvent, User)
│   ├── repository/    Spring Data repos – row-locking queries live here
│   ├── service/       BookingService (reserve/confirm/cancel/expire/reallocate), WaitingQueueService,
│   │                  PaymentGateway (simulator), Schedulers (expiry sweep + delayed-payment settlement)
│   ├── web/           REST controllers, error mapping
│   └── dto/           request/response records
├── src/main/resources/
│   ├── application.yml   all tunables (hold seconds, payment delay, pool sizes…)
│   ├── schema.sql        PostgreSQL DDL incl. the anti-double-booking unique index
│   └── static/           index.html · styles.css · app.js  (the frontend)
├── docs/
│   ├── API.md                      every endpoint + curl examples
│   ├── DIAGRAMS.md                 ERD + booking workflow diagrams (Mermaid)
│   ├── CONCURRENCY_AND_SCALING.md  how double booking is prevented, how to scale
│   └── LOAD_TEST_RESULTS.md        measured concurrent-booking behaviour
├── loadtest/                        k6 script + Java race/rush programs (run with `java loadtest/X.java`)
├── Dockerfile · docker-compose.yml
└── pom.xml
```

## Functionality checklist

| # | Requirement | Where |
|---|---|---|
| 01 | View available seats, reserve, confirm | `GET /api/seats`, `POST /api/reservations`, `POST …/confirm` |
| 02 | No two users book the same seat | `SELECT … FOR UPDATE` on the seat row **+** partial unique index `uq_reservations_live_seat` |
| 03 | Temporary reservations that expire | `expires_at` + `Schedulers.expireHolds` (every 2 s, `SKIP LOCKED`) + lazy expiry on next touch |
| 04 | Cancellation & seat reallocation | `POST …/cancel` → `BookingService.releaseSeat` (queue head or AVAILABLE) |
| 05 | Payments: success / failed / delayed | `PaymentGateway` + `paymentMode` on confirm; delayed ones settled by a job |
| 06 | Consistency on repeated / concurrent requests | idempotent reserve/confirm/cancel; DB row locks; unique constraints |
| 07 | Booking history & reservation status APIs | `GET /api/users/{id}/bookings`, `GET /api/reservations/{id}`, `…/history` |
| Bonus | Waiting queue at capacity | `POST /api/queue/join` · automatic hand-off of freed seats, FIFO, `SKIP LOCKED` |

Full endpoint reference: [docs/API.md](docs/API.md).

---

## 1. Run with Docker (recommended)

Prerequisites: Docker Desktop (or Docker Engine + compose plugin).

```bash
git clone <this repo> last-seat && cd last-seat
docker compose up --build
```

* App: **http://localhost:8080** (frontend) · **http://localhost:8080/api/seats/summary** (API)
* PostgreSQL: `localhost:5432`, db `lastseat`, user `lastseat`, password `lastseat`
* Schema is created automatically on first start (`schema.sql`, idempotent) and 500 seats are seeded.

Stop with `Ctrl-C`; `docker compose down -v` also wipes the database volume.

Run the k6 load test inside Docker (no local k6 needed):

```bash
docker compose --profile loadtest run --rm k6
```

Scale the backend to 3 instances (correctness is unaffected — all locking is in PostgreSQL):

```bash
docker compose up --scale app=3        # then put nginx/traefik in front, or test each :8080 port
```

## 2. Run locally (IDE / terminal)

Prerequisites: JDK 21, Maven 3.9, PostgreSQL 16 running locally.

### 2a. Create the database — with DBeaver

1. **Database → New Database Connection → PostgreSQL**
   Host `localhost`, Port `5432`, Database `postgres`, user/password of your Postgres install → Finish.
2. In the SQL editor of that connection run:
   ```sql
   CREATE USER lastseat WITH PASSWORD 'lastseat';
   CREATE DATABASE lastseat OWNER lastseat;
   ```
3. Create a second connection to database **`lastseat`** (user `lastseat` / `lastseat`).
   *Optional:* open `src/main/resources/schema.sql` in that editor and execute it (Alt+X). If you
   skip this, Spring Boot runs the same file at startup.
4. After the app has started once, refresh **Tables** in DBeaver: `users`, `seats` (500 rows),
   `reservations`, `payments`, `waiting_queue`, `booking_events`.

Handy DBeaver queries while testing:

```sql
-- live inventory
SELECT status, count(*) FROM seats GROUP BY status;
-- holds about to expire
SELECT id, seat_id, user_id, expires_at - now() AS ttl FROM reservations WHERE status='HELD' ORDER BY expires_at;
-- prove there is never a double booking (must return 0 rows)
SELECT seat_id, count(*) FROM reservations
 WHERE status IN ('HELD','PAYMENT_PENDING','CONFIRMED') GROUP BY seat_id HAVING count(*) > 1;
-- the queue, in order
SELECT q.id, u.name, q.status, q.joined_at FROM waiting_queue q JOIN users u ON u.id=q.user_id ORDER BY q.joined_at;
```

### 2b. Start the backend

```bash
mvn spring-boot:run
# or
mvn -DskipTests package && java -jar target/last-seat.jar
```

Override anything with environment variables (all defaults are in `application.yml`):

| variable | default | meaning |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/lastseat` | JDBC URL |
| `DB_USER` / `DB_PASSWORD` | `lastseat` / `lastseat` | credentials |
| `HOLD_SECONDS` | `120` | how long a temporary reservation lives |
| `EXPIRY_SWEEP_MS` | `2000` | expiry job interval |
| `PAYMENT_DELAY_SECONDS` | `15` | simulated slow-gateway delay |
| `DB_POOL_SIZE` | `30` | Hikari pool |
| `TOMCAT_MAX_THREADS` | `200` | request threads |
| `ADMIN_RESET_ENABLED` | `true` | enables `POST /api/admin/reset` (turn **off** in production) |

Open **http://localhost:8080**.

## 3. Using the frontend

1. Enter a name and e-mail → *Continue* (creates/looks up the user; stored in `localStorage`).
2. Click a **green** seat → it turns **blue** (yours) with a 2-minute countdown under *My reservations*.
3. Choose a payment simulation (Success / Fail / Delayed / Random) → **Pay & confirm**.
4. Open a second browser profile (or incognito) as a different student and try to click the same seat → you get the 409 toast.
5. Let a hold run out → it flips to EXPIRED and the seat is green again (or goes to the queue head).
6. When all 500 seats are held/booked, the *Join waiting queue* banner appears. Cancel a booking from another user and watch the queued user receive the seat automatically.

## 4. Example API requests

```bash
# register two students
curl -s -X POST localhost:8080/api/users -H 'Content-Type: application/json' -d '{"name":"Asha","email":"asha@college.edu"}'
curl -s -X POST localhost:8080/api/users -H 'Content-Type: application/json' -d '{"name":"Ravi","email":"ravi@college.edu"}'

# both go for seat 1 — only one wins
curl -s -X POST localhost:8080/api/reservations -H 'Content-Type: application/json' -d '{"userId":1,"seatId":1}'   # 201 HELD
curl -s -X POST localhost:8080/api/reservations -H 'Content-Type: application/json' -d '{"userId":2,"seatId":1}'   # 409

# Asha pays
curl -s -X POST localhost:8080/api/reservations/1/confirm -H 'Content-Type: application/json' -d '{"userId":1,"paymentMode":"SUCCESS"}'

# status, history
curl -s localhost:8080/api/reservations/1
curl -s localhost:8080/api/users/1/bookings
curl -s localhost:8080/api/reservations/1/history

# cancel → seat reallocated
curl -s -X POST localhost:8080/api/reservations/1/cancel -H 'Content-Type: application/json' -d '{"userId":1}'

# queue (only works once atCapacity == true)
curl -s -X POST localhost:8080/api/queue/join -H 'Content-Type: application/json' -d '{"userId":2}'
curl -s localhost:8080/api/queue/2
```

More in [docs/API.md](docs/API.md).

## 5. Load testing

```bash
# k6 (https://k6.io) – full "6 PM rush" scenario, 200 VUs by default
k6 run loadtest/booking-rush.js
k6 run -e VUS=500 -e DURATION=60s loadtest/booking-rush.js

# or, with nothing but the JDK (single-file programs, no build step):
java loadtest/SameSeatRace.java 200          # 200 users, 1 seat, same instant → exactly one 201
java loadtest/Rush.java 300 10               # full rush scenario: 300 VUs x 10 iterations
```

All scripts end with `POST /api/admin/verify`, which asks PostgreSQL whether any seat has more
than one live reservation. Measured results: [docs/LOAD_TEST_RESULTS.md](docs/LOAD_TEST_RESULTS.md).

## 6. Design documents

* [docs/DIAGRAMS.md](docs/DIAGRAMS.md) — ERD, reservation state machine, concurrent-reserve sequence, reallocation flow.
* [docs/CONCURRENCY_AND_SCALING.md](docs/CONCURRENCY_AND_SCALING.md) — the three layers that prevent double booking, expiry, payment states, idempotency, waiting queue, and the scaling roadmap.