import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Same-seat race test, Java edition (JDK 11+, no dependencies).
 *
 *   java loadtest/SameSeatRace.java                 # 200 users, seats 1..3, http://localhost:8080
 *   java loadtest/SameSeatRace.java 500 1 5 http://localhost:8080
 *
 * N users fire a reserve request for the SAME seat at the same instant (released by a
 * CountDownLatch). Exactly one must receive 201 Created; everyone else must receive 409.
 * At the end the database itself is asked whether any seat has more than one live reservation.
 */
public class SameSeatRace {
    static final HttpClient http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
    static String base;

    public static void main(String[] args) throws Exception {
        int users = args.length > 0 ? Integer.parseInt(args[0]) : 200;
        int firstSeat = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        int rounds = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        base = args.length > 3 ? args[3] : "http://localhost:8080";

        post("/api/admin/reset", null);
        List<Long> userIds = new ArrayList<>();
        for (int i = 0; i < users; i++) {
            String body = post("/api/users", "{\"name\":\"racer-" + i + "\",\"email\":\"racer" + i + "@college.edu\"}").body();
            userIds.add(extractId(body));
        }

        ExecutorService pool = Executors.newFixedThreadPool(users);
        for (int round = 1; round <= rounds; round++) {
            long seatId = firstSeat + round - 1;
            CountDownLatch start = new CountDownLatch(1);
            ConcurrentMap<Integer, Integer> counts = new ConcurrentHashMap<>();
            List<Long> winners = Collections.synchronizedList(new ArrayList<>());
            List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
            List<Future<?>> futures = new ArrayList<>();

            for (long uid : userIds) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        long t0 = System.nanoTime();
                        HttpResponse<String> r = post("/api/reservations", "{\"userId\":" + uid + ",\"seatId\":" + seatId + "}");
                        latencies.add((System.nanoTime() - t0) / 1_000_000);
                        counts.merge(r.statusCode(), 1, Integer::sum);
                        if (r.statusCode() == 201) winners.add(uid);
                    } catch (Exception e) { counts.merge(-1, 1, Integer::sum); }
                    return null;
                }));
            }
            long wall0 = System.nanoTime();
            start.countDown();                       // release everyone at once
            for (Future<?> f : futures) f.get();
            long wallMs = (System.nanoTime() - wall0) / 1_000_000;

            Collections.sort(latencies);
            System.out.printf("round %d seat %d: %d simultaneous reserves in %d ms -> %s | winners=%s | latency p50=%dms p95=%dms max=%dms%n",
                    round, seatId, users, wallMs, new TreeMap<>(counts), winners,
                    pct(latencies, .5), pct(latencies, .95), latencies.get(latencies.size() - 1));
            if (counts.getOrDefault(201, 0) != 1) throw new AssertionError("DOUBLE BOOKING DETECTED on seat " + seatId);
        }
        pool.shutdown();
        System.out.println("db verify: " + post("/api/admin/verify", null).body());
    }

    static HttpResponse<String> post(String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
        b.POST(json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static long extractId(String json) {
        Matcher m = Pattern.compile("\"id\":(\\d+)").matcher(json);
        if (!m.find()) throw new IllegalStateException("no id in " + json);
        return Long.parseLong(m.group(1));
    }

    static long pct(List<Long> sorted, double q) { return sorted.get(Math.min(sorted.size() - 1, (int) (q * sorted.size()))); }
}
