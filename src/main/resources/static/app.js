/* The Last Seat – frontend. Plain JS, talks to the Spring Boot REST API on the same origin. */
const API = '';
const $ = (id) => document.getElementById(id);

const state = {
  user: JSON.parse(localStorage.getItem('ls.user') || 'null'),
  seats: [],
  summary: null,
  mine: [],
  queue: null,
  pollTimer: null,
};

// ---------- tiny helpers ----------
async function api(path, opts = {}) {
  const res = await fetch(API + path, {
    headers: { 'Content-Type': 'application/json' },
    ...opts,
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) {
    const err = new Error((data && data.message) || res.statusText);
    err.status = res.status; err.data = data;
    throw err;
  }
  return data;
}

function toast(msg, kind = '') {
  const el = document.createElement('div');
  el.className = `toast ${kind}`;
  el.textContent = msg;
  $('toasts').appendChild(el);
  setTimeout(() => el.remove(), 4200);
}

const fmtTime = (iso) => iso ? new Date(iso).toLocaleTimeString() : '';
const mmss = (s) => `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;

// ---------- auth ----------
$('auth-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  try {
    state.user = await api('/api/users', { method: 'POST', body: { name: $('auth-name').value, email: $('auth-email').value } });
    localStorage.setItem('ls.user', JSON.stringify(state.user));
    renderAuth();
    toast(`Welcome, ${state.user.name}!`, 'ok');
    refresh();
  } catch (err) { toast(err.message, 'err'); }
});
$('auth-logout').addEventListener('click', () => {
  state.user = null; state.mine = []; state.queue = null;
  localStorage.removeItem('ls.user');
  renderAuth(); renderMine(); renderQueue(); renderSeats();
});
function renderAuth() {
  const signed = !!state.user;
  $('auth-form').classList.toggle('hidden', signed);
  $('auth-user').classList.toggle('hidden', !signed);
  if (signed) { $('auth-user-name').textContent = state.user.name; $('auth-user-email').textContent = state.user.email; }
}

// ---------- seats ----------
function renderSeats() {
  const myLive = new Set(state.mine.filter(r => ['HELD', 'PAYMENT_PENDING', 'CONFIRMED'].includes(r.status)).map(r => r.seatId));
  const bySection = {};
  for (const s of state.seats) (bySection[s.section] ||= []).push(s);
  const frag = document.createDocumentFragment();
  for (const [section, seats] of Object.entries(bySection)) {
    const box = document.createElement('div'); box.className = 'section';
    const price = seats[0].price;
    box.innerHTML = `<div class="section-title"><span>Section ${section}</span><span>₹${price}</span></div>`;
    const grid = document.createElement('div'); grid.className = 'grid';
    for (const seat of seats) {
      const b = document.createElement('button');
      const cls = myLive.has(seat.id) ? 'mine' : seat.status.toLowerCase();
      b.className = `seat ${cls}`;
      b.title = `${seat.seatNumber} · ${seat.status}`;
      b.textContent = seat.number;
      if (cls === 'available') b.addEventListener('click', () => reserve(seat));
      grid.appendChild(b);
    }
    box.appendChild(grid); frag.appendChild(box);
  }
  $('seatmap').replaceChildren(frag);
  const atCapacity = state.summary && state.summary.atCapacity;
  $('capacity-banner').classList.toggle('hidden', !atCapacity || !state.user || (state.queue && state.queue.status === 'WAITING'));
}

function renderSummary() {
  const s = state.summary; if (!s) return;
  $('st-available').textContent = s.available;
  $('st-held').textContent = s.held;
  $('st-booked').textContent = s.booked;
  $('st-queue').textContent = s.waitingInQueue;
}

async function reserve(seat) {
  if (!state.user) return toast('Sign in first to reserve a seat.', 'err');
  try {
    const r = await api('/api/reservations', { method: 'POST', body: { userId: state.user.id, seatId: seat.id } });
    toast(`${r.seatNumber}: ${r.message}`, 'ok');
    await refresh();
  } catch (err) {
    toast(err.message, 'err');
    refresh();
  }
}

// ---------- my reservations ----------
function card(r, withActions) {
  const el = document.createElement('div'); el.className = 'card';
  const live = r.status === 'HELD' || r.status === 'PAYMENT_PENDING';
  el.innerHTML = `
    <div>
      <div class="title">Seat ${r.seatNumber} <span class="muted small">· ₹${r.price} · ${r.source === 'WAITING_QUEUE' ? 'from queue' : 'direct'}</span></div>
      <div class="sub">${live ? `expires in <span class="timer" data-expires="${r.expiresAt}">--:--</span>` : `${r.status === 'CONFIRMED' ? 'confirmed ' + fmtTime(r.confirmedAt) : 'reserved ' + fmtTime(r.reservedAt)}`}
        ${r.payment ? ` · payment <b>${r.payment.status}</b> (${r.payment.mode})` : ''}</div>
    </div>
    <span class="badge ${r.status}">${r.status.replace('_', ' ')}</span>`;
  if (withActions) {
    const actions = document.createElement('div'); actions.className = 'actions';
    if (r.status === 'HELD') {
      const pay = document.createElement('button'); pay.className = 'btn primary small'; pay.textContent = 'Pay & confirm';
      pay.onclick = () => confirmRes(r); actions.appendChild(pay);
    }
    if (live || r.status === 'CONFIRMED') {
      const cancel = document.createElement('button'); cancel.className = 'btn ghost small danger'; cancel.textContent = 'Cancel';
      cancel.onclick = () => cancelRes(r); actions.appendChild(cancel);
    }
    const hist = document.createElement('button'); hist.className = 'btn ghost small'; hist.textContent = 'Timeline';
    hist.onclick = () => showTimeline(r); actions.appendChild(hist);
    el.appendChild(actions);
  }
  return el;
}

function renderMine() {
  const live = state.mine.filter(r => ['HELD', 'PAYMENT_PENDING', 'CONFIRMED'].includes(r.status));
  $('my-list').replaceChildren(...(live.length ? live.map(r => card(r, true)) : [Object.assign(document.createElement('p'), { className: 'muted', textContent: state.user ? 'Nothing live. Click an available seat.' : 'Sign in to see your reservations.' })]));
  $('history-list').replaceChildren(...state.mine.map(r => card(r, false)));
}

async function confirmRes(r) {
  const mode = $('pay-mode').value;
  try {
    const res = await api(`/api/reservations/${r.id}/confirm`, { method: 'POST', body: { userId: state.user.id, paymentMode: mode } });
    toast(`${res.seatNumber}: ${res.message}`, res.status === 'CONFIRMED' ? 'ok' : '');
  } catch (err) {
    toast(err.message, 'err');
  }
  refresh();
}

async function cancelRes(r) {
  try {
    const res = await api(`/api/reservations/${r.id}/cancel`, { method: 'POST', body: { userId: state.user.id } });
    toast(res.message, 'ok');
  } catch (err) { toast(err.message, 'err'); }
  refresh();
}

async function showTimeline(r) {
  try {
    const ev = await api(`/api/reservations/${r.id}/history`);
    alert(ev.map(e => `${fmtTime(e.createdAt)}  ${e.eventType}\n   ${e.details || ''}`).join('\n'));
  } catch (err) { toast(err.message, 'err'); }
}

// countdown ticks every second without hitting the server
setInterval(() => {
  const now = Date.now();
  document.querySelectorAll('.timer[data-expires]').forEach(el => {
    const left = Math.max(0, Math.round((new Date(el.dataset.expires) - now) / 1000));
    el.textContent = mmss(left);
    el.classList.toggle('urgent', left <= 20);
  });
}, 1000);

// ---------- waiting queue ----------
async function joinQueue() {
  if (!state.user) return toast('Sign in first.', 'err');
  try {
    const q = await api('/api/queue/join', { method: 'POST', body: { userId: state.user.id } });
    toast(`${q.message} (position ${q.position})`, 'ok');
  } catch (err) { toast(err.message, 'err'); }
  refresh();
}
async function leaveQueue() {
  try { await api(`/api/queue/${state.user.id}`, { method: 'DELETE' }); toast('Left the queue.'); }
  catch (err) { toast(err.message, 'err'); }
  refresh();
}
$('queue-join').onclick = joinQueue;
$('queue-join-2').onclick = joinQueue;
$('queue-leave').onclick = leaveQueue;

function renderQueue() {
  const q = state.queue;
  const waiting = q && q.status === 'WAITING';
  $('queue-leave').classList.toggle('hidden', !waiting);
  $('queue-join-2').classList.toggle('hidden', waiting);
  $('queue-join-2').disabled = !state.user || !(state.summary && state.summary.atCapacity);
  $('queue-join-2').title = state.summary && !state.summary.atCapacity ? 'Seats are still available – reserve one directly' : '';
  if (!state.user) { $('queue-status').textContent = 'Sign in to join the queue.'; return; }
  if (!q || q.status === 'LEFT') { $('queue-status').textContent = 'Not in the queue.'; return; }
  if (waiting) $('queue-status').innerHTML = `You are <b>#${q.position}</b> in line (${q.waitingAhead} ahead). Joined ${fmtTime(q.joinedAt)}.`;
  else if (q.status === 'ASSIGNED') $('queue-status').innerHTML = `🎉 Seat <b>${q.assignedReservation ? q.assignedReservation.seatNumber : ''}</b> was handed to you at ${fmtTime(q.assignedAt)} – confirm it under "My reservations" before it expires.`;
}

// ---------- polling ----------
let lastQueueStatus = null;
async function refresh() {
  try {
    const [seats, summary] = await Promise.all([api('/api/seats'), api('/api/seats/summary')]);
    state.seats = seats; state.summary = summary;
    if (state.user) {
      state.mine = await api(`/api/users/${state.user.id}/bookings`);
      try { state.queue = await api(`/api/queue/${state.user.id}`); } catch (e) { if (e.status === 404) state.queue = null; else throw e; }
      if (state.queue && state.queue.status === 'ASSIGNED' && lastQueueStatus === 'WAITING') toast('A seat was handed to you from the queue – confirm it now!', 'ok');
      lastQueueStatus = state.queue ? state.queue.status : null;
    }
    renderSummary(); renderSeats(); renderMine(); renderQueue();
  } catch (err) { console.error(err); }
}

renderAuth();
refresh();
state.pollTimer = setInterval(refresh, 3000);
