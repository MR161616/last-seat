# REST API reference

Base URL: `http://localhost:8080`. All bodies are JSON. Errors have the shape
`{"status":409,"error":"Conflict","message":"…","timestamp":"…"}`.

## Users

| Method | Path | Body | Response |
|---|---|---|---|
| `POST` | `/api/users` | `{"name","email"}` | `201` user (idempotent on e-mail) |
| `GET` | `/api/users/{id}` | – | user |
| `GET` | `/api/users/{id}/bookings` | – | **booking history**, newest first, with latest payment |
| `GET` | `/api/users/{id}/events` | – | audit events for the user (reservations + queue) |

## Seats

| Method | Path | Query | Response |
|---|---|---|---|
| `GET` | `/api/seats` | `status=AVAILABLE\|HELD\|BOOKED` (optional) | list of seats |
| `GET` | `/api/seats/summary` | – | `{total, available, held, booked, waitingInQueue, atCapacity}` |
| `GET` | `/api/seats/{id}` | – | one seat |

## Reservations

| Method | Path | Body | Response codes |
|---|---|---|---|
| `POST` | `/api/reservations` | `{"userId","seatId"}` | `201` new hold · `200` you already hold it · `409` someone else does · `404` |
| `GET` | `/api/reservations/{id}` | – | **reservation status** incl. `secondsRemaining` |
| `POST` | `/api/reservations/{id}/confirm` | `{"userId","paymentMode"}` | `200` CONFIRMED · `202` PAYMENT_PENDING · `402` PAYMENT_FAILED · `410` expired/cancelled · `403` not yours |
| `POST` | `/api/reservations/{id}/cancel` | `{"userId"}` | `200` CANCELLED (idempotent) · `410` already terminal · `403` |
| `GET` | `/api/reservations/{id}/history` | – | event timeline of this reservation |

`paymentMode` ∈ `SUCCESS` (default) · `FAIL` · `DELAYED` · `RANDOM`.

Reservation object:

```json
{
  "id": 42, "userId": 7, "seatId": 13, "seatNumber": "A-02-03", "section": "A", "price": 899.00,
  "status": "HELD", "source": "DIRECT",
  "reservedAt": "2026-10-15T12:30:00Z", "expiresAt": "2026-10-15T12:32:00Z", "secondsRemaining": 117,
  "confirmedAt": null, "cancelledAt": null,
  "payment": null,
  "message": "Seat held for 120 seconds"
}
```

## Waiting queue (bonus)

| Method | Path | Body | Response |
|---|---|---|---|
| `POST` | `/api/queue/join` | `{"userId"}` | `201` entry with `position` · `409` seats still available · `200` already queued |
| `GET` | `/api/queue/{userId}` | – | `{status: WAITING\|ASSIGNED\|LEFT, position, waitingAhead, assignedReservation}` |
| `DELETE` | `/api/queue/{userId}` | – | leave the queue |
| `GET` | `/api/queue` | – | `{waiting, atCapacity, head:[…first 10]}` |

## Admin / ops (disable with `ADMIN_RESET_ENABLED=false`)

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/admin/reset` | wipe reservations/payments/queue/events, set all seats AVAILABLE |
| `POST` | `/api/admin/verify` | DB consistency check: `{doubleBookedSeats, seatStatusMismatches, consistent}` |
| `GET` | `/actuator/health` | liveness |
| `GET` | `/actuator/metrics` | Micrometer metrics (Hikari pool, HTTP timings, JVM) |

## Example session (curl)

```bash
# 1. register
curl -s -X POST localhost:8080/api/users -H 'Content-Type: application/json' \
  -d '{"name":"Asha","email":"asha@college.edu"}'
# → {"id":1,"name":"Asha",...}

# 2. look at the front row
curl -s 'localhost:8080/api/seats?status=AVAILABLE' | head -c 300

# 3. hold seat 1 for 120 s
curl -s -X POST localhost:8080/api/reservations -H 'Content-Type: application/json' \
  -d '{"userId":1,"seatId":1}'
# → 201 {"id":1,"status":"HELD","secondsRemaining":120,...}

# 3b. a second student tries the same seat
curl -s -X POST localhost:8080/api/reservations -H 'Content-Type: application/json' \
  -d '{"userId":2,"seatId":1}'
# → 409 {"message":"Seat A-01-01 is already held"}

# 4. pay (simulate a slow gateway)
curl -s -X POST localhost:8080/api/reservations/1/confirm -H 'Content-Type: application/json' \
  -d '{"userId":1,"paymentMode":"DELAYED"}'
# → 202 {"status":"PAYMENT_PENDING",...}      ...about 15 s later:
curl -s localhost:8080/api/reservations/1
# → {"status":"CONFIRMED","payment":{"status":"SUCCESS",...}}

# 5. history
curl -s localhost:8080/api/users/1/bookings
curl -s localhost:8080/api/reservations/1/history

# 6. cancel → seat goes to the queue head (if any) or back to AVAILABLE
curl -s -X POST localhost:8080/api/reservations/1/cancel -H 'Content-Type: application/json' -d '{"userId":1}'

# 7. sold out? join the queue
curl -s -X POST localhost:8080/api/queue/join -H 'Content-Type: application/json' -d '{"userId":2}'
curl -s localhost:8080/api/queue/2
```

A ready-to-import Postman/Insomnia-style collection is not required: every call above is a
single `curl`, and the bundled frontend at `http://localhost:8080/` exercises all of them.
