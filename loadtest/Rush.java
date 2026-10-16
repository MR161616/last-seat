import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "6 PM rush" load test, Java edition (JDK 11+, no dependencies). Same scenario as booking-rush.js (k6):
 *
 *  each virtual user, N times:  pick a seat (70% from the 50 hot front-row seats) -> reserve
 *      -> re-send the same reserve (double-click; must return 200 with the same id)
 *      -> think -> 15% abandon (hold expires) | pay SUCCESS 60% / FAIL 15% / DELAYED 10%
 *      -> 10% of successful payers cancel afterwards
 *      -> if the venue is sold out, join the waiting queue
 *
 *   java loadtest/Rush.java                       # 300 users x 10 iterations, 1.0 s think time
 *   java loadtest/Rush.java 500 10 0.5 http://localhost:8080
 */
public class Rush {
    static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    static String base;
    static final ConcurrentMap<String, AtomicInteger> stats = new ConcurrentHashMap<>();
    static final Map<String, List<Long>> latencies = new ConcurrentHashMap<>();
    static final Random rnd = new Random();

    record Resp(int status, String body, long ms) {}

    public static void main(String[] args) throws Exception {
        int users = args.length > 0 ? Integer.parseInt(args[0]) : 300;
        int iterations = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        double think = args.length > 2 ? Double.parseDouble(args[2]) : 1.0;
        base = args.length > 3 ? args[3] : "http://localhost:8080";
        for (String k : List.of("reserve", "confirm", "all")) latencies.put(k, Collections.synchronizedList(new ArrayList<>()));

        call("POST", "/api/admin/reset", null);
        List<Long> seatIds = extractIds(call("GET", "/api/seats", null).body());
        List<Long> userIds = new ArrayList<>();
        for (int i = 0; i < users; i++)
            userIds.add(extractId(call("POST", "/api/users", "{\"name\":\"vu-" + i + "\",\"email\":\"vu" + i + "@college.edu\"}").body()));

        System.out.printf("starting %d VUs x %d iterations against %s%n", users, iterations, base);
        ExecutorService pool = Executors.newFixedThreadPool(users);
        long t0 = System.nanoTime();
        List<Future<?>> fs = new ArrayList<>();
        for (long uid : userIds) fs.add(pool.submit(() -> { vu(uid, seatIds, iterations, think); return null; }));
        for (Future<?> f : fs) f.get();
        double wall = (System.nanoTime() - t0) / 1e9;
        pool.shutdown();

        int total = latencies.get("all").size();
        System.out.printf("%n== %d requests in %.1fs  (%.0f req/s) ==%n", total, wall, total / wall);
        new TreeMap<>(stats).forEach((k, v) -> System.out.printf("  %-28s %d%n", k, v.get()));
        for (String k : List.of("reserve", "confirm", "all")) {
            List<Long> xs = new ArrayList<>(latencies.get(k)); Collections.sort(xs);
            System.out.printf("  latency %-8s p50=%dms p95=%dms p99=%dms max=%dms%n", k, pct(xs, .5), pct(xs, .95), pct(xs, .99), xs.isEmpty() ? 0 : xs.get(xs.size() - 1));
        }
        System.out.println("  seats summary: " + call("GET", "/api/seats/summary", null).body());
        System.out.println("  db verify    : " + call("POST", "/api/admin/verify", null).body());
    }

    static void vu(long uid, List<Long> seatIds, int iterations, double think) {
        try {
            for (int i = 0; i < iterations; i++) {
                boolean hot = rnd.nextDouble() < 0.7;
                long seat = hot ? seatIds.get(rnd.nextInt(Math.min(50, seatIds.size()))) : seatIds.get(rnd.nextInt(seatIds.size()));
                Resp r = call("POST", "/api/reservations", "{\"userId\":" + uid + ",\"seatId\":" + seat + "}"); rec("reserve", r);
                if (r.status() == 409) {
                    Resp s = call("GET", "/api/seats/summary", null); rec("summary", s);
                    if (s.body().contains("\"atCapacity\":true")) { rec("queue_join", call("POST", "/api/queue/join", "{\"userId\":" + uid + "}")); }
                    sleep(think * 0.3); continue;
                }
                if (r.status() != 200 && r.status() != 201) continue;
                long rid = extractId(r.body());
                Resp dup = call("POST", "/api/reservations", "{\"userId\":" + uid + ",\"seatId\":" + seat + "}"); rec("reserve_dup", dup);
                if (!(dup.status() == 200 && extractId(dup.body()) == rid)) inc("IDEMPOTENCY_VIOLATION");
                sleep(rnd.nextDouble() * think);
                double roll = rnd.nextDouble();
                if (roll < 0.15) continue;   // abandon -> hold expires
                String mode = roll < 0.75 ? "SUCCESS" : roll < 0.9 ? "FAIL" : "DELAYED";
                Resp c = call("POST", "/api/reservations/" + rid + "/confirm", "{\"userId\":" + uid + ",\"paymentMode\":\"" + mode + "\"}"); rec("confirm", c);
                if (c.status() == 200 && rnd.nextDouble() < 0.1) rec("cancel", call("POST", "/api/reservations/" + rid + "/cancel", "{\"userId\":" + uid + "}"));
                sleep(think * 0.5);
            }
        } catch (Exception e) { inc("ERROR_" + e.getClass().getSimpleName()); }
    }

    static Resp call(String method, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
        if (method.equals("GET")) b.GET(); else b.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        long t0 = System.nanoTime();
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body(), (System.nanoTime() - t0) / 1_000_000);
    }

    static void rec(String kind, Resp r) {
        inc(kind + "_" + r.status());
        latencies.get("all").add(r.ms());
        if (latencies.containsKey(kind)) latencies.get(kind).add(r.ms());
    }
    static void inc(String key) { stats.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet(); }
    static void sleep(double seconds) throws InterruptedException { Thread.sleep((long) (seconds * 1000)); }

    static long extractId(String json) {
        Matcher m = Pattern.compile("\"id\":(\\d+)").matcher(json);
        if (!m.find()) throw new IllegalStateException("no id in " + json);
        return Long.parseLong(m.group(1));
    }
    static List<Long> extractIds(String json) {
        List<Long> ids = new ArrayList<>();
        Matcher m = Pattern.compile("\\{\"id\":(\\d+)").matcher(json);
        while (m.find()) ids.add(Long.parseLong(m.group(1)));
        return ids;
    }
    static long pct(List<Long> sorted, double q) { return sorted.isEmpty() ? 0 : sorted.get(Math.min(sorted.size() - 1, (int) (q * sorted.size()))); }
}
