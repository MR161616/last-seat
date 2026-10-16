// k6 load test: "6 PM rush" – many virtual users fight for the same 500 seats.
//   k6 run loadtest/booking-rush.js
//   k6 run -e BASE_URL=http://localhost:8080 -e VUS=300 -e DURATION=60s loadtest/booking-rush.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const headers = { 'Content-Type': 'application/json' };

const reserveOk = new Counter('reserve_created_201');
const reserveDup = new Counter('reserve_repeat_200');
const reserveConflict = new Counter('reserve_conflict_409');
const confirmOk = new Counter('confirm_ok_200');
const confirmFail = new Counter('confirm_payment_failed_402');
const confirmGone = new Counter('confirm_gone_410');
const queueJoined = new Counter('queue_joined_201');
const reserveLatency = new Trend('reserve_latency_ms', true);

export const options = {
  scenarios: {
    rush: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: Number(__ENV.VUS || 200) },
        { duration: __ENV.DURATION || '45s', target: Number(__ENV.VUS || 200) },
        { duration: '5s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],          // 409/410/402 are *expected* business outcomes, excluded below
    'http_req_duration{expected:true}': ['p(95)<1500'],
  },
};

export function setup() {
  http.post(`${BASE}/api/admin/reset`);
  const seats = http.get(`${BASE}/api/seats`).json();
  return { seatIds: seats.map(s => s.id) };
}

export default function (data) {
  const vu = __VU, iter = __ITER;
  const user = http.post(`${BASE}/api/users`, JSON.stringify({ name: `vu-${vu}`, email: `vu${vu}@college.edu` }), { headers }).json();

  // hot-spot: everyone wants the first 50 seats (front row) with 70% probability
  const hot = Math.random() < 0.7;
  const seatId = hot ? data.seatIds[Math.floor(Math.random() * 50)] : data.seatIds[Math.floor(Math.random() * data.seatIds.length)];

  const res = http.post(`${BASE}/api/reservations`, JSON.stringify({ userId: user.id, seatId }),
    { headers, responseCallback: http.expectedStatuses(200, 201, 409), tags: { expected: 'true' } });
  reserveLatency.add(res.timings.duration);
  check(res, { 'reserve: 201/200/409': r => [200, 201, 409].includes(r.status) });

  if (res.status === 201) reserveOk.add(1);
  else if (res.status === 200) reserveDup.add(1);
  else if (res.status === 409) {
    reserveConflict.add(1);
    const summary = http.get(`${BASE}/api/seats/summary`).json();
    if (summary.atCapacity) {
      const q = http.post(`${BASE}/api/queue/join`, JSON.stringify({ userId: user.id }), { headers, responseCallback: http.expectedStatuses(201, 409) });
      if (q.status === 201) queueJoined.add(1);
    }
    sleep(0.3);
    return;
  }

  const r = res.json();
  // simulate a duplicate submit (double-click): must come back 200 with the same reservation id
  const dup = http.post(`${BASE}/api/reservations`, JSON.stringify({ userId: user.id, seatId }), { headers });
  check(dup, { 'repeat reserve is idempotent': d => d.status === 200 && d.json().id === r.id });

  sleep(Math.random() * 1.5);   // "thinking" before paying
  const roll = Math.random();
  if (roll < 0.15) {            // abandon the booking -> hold will expire
    return;
  }
  const mode = roll < 0.75 ? 'SUCCESS' : roll < 0.9 ? 'FAIL' : 'DELAYED';
  const c = http.post(`${BASE}/api/reservations/${r.id}/confirm`, JSON.stringify({ userId: user.id, paymentMode: mode }),
    { headers, responseCallback: http.expectedStatuses(200, 202, 402, 410) });
  if (c.status === 200) confirmOk.add(1);
  else if (c.status === 402) confirmFail.add(1);
  else if (c.status === 410) confirmGone.add(1);

  if (c.status === 200 && Math.random() < 0.1) {   // 10% cancel after paying -> seat reallocated
    http.post(`${BASE}/api/reservations/${r.id}/cancel`, JSON.stringify({ userId: user.id }), { headers });
  }
  sleep(0.5);
}

export function teardown() {
  const v = http.post(`${BASE}/api/admin/verify`).json();
  console.log(`CONSISTENCY CHECK -> doubleBookedSeats=${v.doubleBookedSeats} seatStatusMismatches=${v.seatStatusMismatches} consistent=${v.consistent}`);
  const s = http.get(`${BASE}/api/seats/summary`).json();
  console.log(`FINAL INVENTORY -> available=${s.available} held=${s.held} booked=${s.booked} queue=${s.waitingInQueue}`);
}
