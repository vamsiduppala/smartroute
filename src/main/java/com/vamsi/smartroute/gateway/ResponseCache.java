package com.vamsi.smartroute.gateway;

import com.vamsi.smartroute.routing.RouteResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Exact-match response cache — the most direct cost lever after routing. This gateway exists to
 * make LLM calls cheaper; the cheapest call is the one you don't make. When the same prompt comes
 * back within the TTL, its already-computed {@link RouteResult} is returned for $0 instead of
 * re-routing and re-calling a model.
 *
 * <p>Scope, stated plainly (the honest limits matter here):
 * <ul>
 *   <li><b>Exact match only</b> — keyed on the prompt string. No semantic/embedding similarity.
 *   <li><b>Keyed on the prompt, not the tenant</b> — an identical prompt yields the same answer
 *       regardless of who asked (nothing tenant-specific enters routing), so sharing across tenants
 *       maximizes the cost saving. It is deliberately NOT a per-tenant store.
 *   <li><b>TTL + size bound</b> — entries expire after {@code ttlSeconds}; the map is capped at
 *       {@code maxEntries} (expired entries are reclaimed first, then new inserts are skipped rather
 *       than growing unbounded). Not an LRU — a production cache would evict by recency.
 *   <li>Only <b>passing</b> results should be cached by the caller; a failed route must not be
 *       served again from cache.
 * </ul>
 * Time is an injected {@link LongSupplier} of nanoseconds so tests drive TTL deterministically.
 */
@Component
public class ResponseCache {

    private final long ttlNanos;
    private final int maxEntries;
    private final LongSupplier nanoClock;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    private record Entry(RouteResult result, long expiryNanos) {}

    @Autowired
    public ResponseCache(
            @Value("${smartroute.cache.ttl-seconds:300}") long ttlSeconds,
            @Value("${smartroute.cache.max-entries:10000}") int maxEntries) {
        this(ttlSeconds, maxEntries, System::nanoTime);
    }

    /** Test seam: inject a controllable nanosecond clock. */
    ResponseCache(long ttlSeconds, int maxEntries, LongSupplier nanoClock) {
        this.ttlNanos = ttlSeconds * 1_000_000_000L;
        this.maxEntries = maxEntries;
        this.nanoClock = nanoClock;
    }

    /** Cached result for this prompt, or null on miss / expiry. Expired entries are dropped lazily. */
    public RouteResult get(String key) {
        Entry e = cache.get(key);
        if (e == null) return null;
        if (nanoClock.getAsLong() >= e.expiryNanos()) {
            cache.remove(key, e);          // lazy expiry; remove(k,v) so a concurrent refresh isn't clobbered
            return null;
        }
        return e.result();
    }

    /** Store a result under the prompt with the configured TTL, staying within the size bound. */
    public void put(String key, RouteResult result) {
        long now = nanoClock.getAsLong();
        if (cache.size() >= maxEntries && !cache.containsKey(key)) {
            cache.entrySet().removeIf(en -> now >= en.getValue().expiryNanos());  // reclaim expired first
            if (cache.size() >= maxEntries) return;   // still full of live entries: skip rather than grow unbounded
        }
        cache.put(key, new Entry(result, now + ttlNanos));
    }

    public int size() { return cache.size(); }
}
