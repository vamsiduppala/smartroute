package com.vamsi.smartroute.gateway;

import com.vamsi.smartroute.model.Tier;
import com.vamsi.smartroute.routing.RouteResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ResponseCacheTest {

    private final AtomicLong nanos = new AtomicLong(0);

    private ResponseCache cacheWith(long ttlSeconds, int maxEntries) {
        return new ResponseCache(ttlSeconds, maxEntries, nanos::get);
    }

    private void advanceSeconds(double seconds) {
        nanos.addAndGet((long) (seconds * 1_000_000_000.0));
    }

    private RouteResult sample(String answer) {
        return new RouteResult(answer, Tier.LUNA, Tier.LUNA, 1, 10, 5, 0.0001, true, "simple");
    }

    @Test
    void returnsNullOnMiss() {
        assertNull(cacheWith(300, 100).get("nope"));
    }

    @Test
    void returnsStoredResultWithinTtl() {
        ResponseCache cache = cacheWith(300, 100);
        cache.put("p", sample("Paris"));
        advanceSeconds(299);
        RouteResult hit = cache.get("p");
        assertNotNull(hit);
        assertEquals("Paris", hit.answer());
    }

    @Test
    void expiresAfterTtl() {
        ResponseCache cache = cacheWith(300, 100);
        cache.put("p", sample("Paris"));
        advanceSeconds(300);                 // exactly at expiry -> already stale
        assertNull(cache.get("p"), "entry should have expired after its TTL");
        assertEquals(0, cache.size(), "expired entry is reclaimed lazily on the get");
    }

    @Test
    void refreshingAKeyResetsItsTtl() {
        ResponseCache cache = cacheWith(300, 100);
        cache.put("p", sample("old"));
        advanceSeconds(200);
        cache.put("p", sample("new"));       // refresh: new 300s window from now
        advanceSeconds(200);                 // 400s since first put, 200s since refresh
        RouteResult hit = cache.get("p");
        assertNotNull(hit, "refresh should have extended the TTL");
        assertEquals("new", hit.answer());
    }

    @Test
    void staysWithinTheSizeBoundAndSkipsNewKeysWhenFullOfLiveEntries() {
        ResponseCache cache = cacheWith(300, 3);
        cache.put("a", sample("A"));
        cache.put("b", sample("B"));
        cache.put("c", sample("C"));         // now full (3 live entries)
        cache.put("d", sample("D"));         // no room, none expired -> skipped
        assertEquals(3, cache.size(), "must not grow past the cap");
        assertNull(cache.get("d"), "the over-cap insert was skipped");
        assertNotNull(cache.get("a"), "existing entries are retained");
    }

    @Test
    void reclaimsExpiredEntriesToMakeRoomForNewOnes() {
        ResponseCache cache = cacheWith(300, 2);
        cache.put("a", sample("A"));
        cache.put("b", sample("B"));         // full
        advanceSeconds(300);                 // a and b now expired
        cache.put("c", sample("C"));         // should reclaim expired, then insert
        assertNotNull(cache.get("c"), "new entry inserted after expired ones were reclaimed");
        assertEquals(1, cache.size());
    }
}
