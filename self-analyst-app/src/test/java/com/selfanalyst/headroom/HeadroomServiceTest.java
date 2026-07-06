package com.selfanalyst.headroom;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadroomServiceTest {

    @Test
    void disabledUsesOriginalBaseUrlAndDoesNotProbe() {
        final boolean[] called = {false};
        HeadroomService service = new HeadroomService(
                false,
                "http://127.0.0.1:8787/v1",
                "https://api.openai.com/v1",
                true,
                false,
                (uri, timeout) -> {
                    called[0] = true;
                    return HeadroomService.ProbeResult.ok("unexpected");
                });

        HeadroomService.Snapshot s = service.snapshot();

        assertEquals("disabled", s.status());
        assertEquals("https://api.openai.com/v1", s.effectiveBaseUrl());
        assertFalse(called[0], "disabled Headroom must not probe the proxy");
    }

    @Test
    void availableProxyWinsEffectiveBaseUrl() {
        HeadroomService service = new HeadroomService(
                true,
                "http://127.0.0.1:8787/v1",
                "https://api.openai.com/v1",
                true,
                true,
                (uri, timeout) -> HeadroomService.ProbeResult.ok("reachable"));

        HeadroomService.Snapshot s = service.snapshot();

        assertEquals("available", s.status());
        assertEquals("http://127.0.0.1:8787/v1", s.effectiveBaseUrl());
        assertEquals("https://api.openai.com/v1", s.originalBaseUrl());
        assertTrue(s.enabled());
        assertTrue(s.outputShaper());
    }

    @Test
    void failedProbeFallsBackToOriginalBaseUrl() {
        HeadroomService service = new HeadroomService(
                true,
                "http://127.0.0.1:8787/v1",
                "https://api.openai.com/v1",
                true,
                false,
                (uri, timeout) -> HeadroomService.ProbeResult.fail("connection refused"));

        HeadroomService.Snapshot s = service.snapshot();

        assertEquals("fallback", s.status());
        assertEquals("https://api.openai.com/v1", s.effectiveBaseUrl());
        assertEquals("connection refused", s.lastError());
        assertEquals("unavailable", s.statsStatus());
    }

    @Test
    void invalidProxyUrlIsUnavailableAndFallsBack() {
        HeadroomService service = new HeadroomService(
                true,
                "not a url",
                "https://api.openai.com/v1",
                true,
                false,
                (uri, timeout) -> HeadroomService.ProbeResult.ok("must not run"));

        HeadroomService.Snapshot s = service.snapshot();

        assertEquals("unavailable", s.status());
        assertEquals("https://api.openai.com/v1", s.effectiveBaseUrl());
        assertTrue(s.lastError().contains("Invalid Headroom proxy URL"), s.lastError());
    }

    @Test
    void snapshotMapContainsOnlySafeDiagnostics() {
        HeadroomService service = new HeadroomService(
                true,
                "http://127.0.0.1:8787/v1",
                "https://api.openai.com/v1",
                false,
                false,
                (URI uri, Duration timeout) -> HeadroomService.ProbeResult.ok("reachable"));

        var map = service.snapshot().toMap();

        assertEquals("available", map.get("status"));
        assertEquals(true, map.get("enabled"));
        assertEquals("http://127.0.0.1:8787/v1", map.get("proxyUrl"));
        assertEquals("https://api.openai.com/v1", map.get("originalBaseUrl"));
        assertEquals("disabled", map.get("statsStatus"));
    }

    @Test
    void ipv6ProxyUrlRootsToValidHttpUri() {
        URI root = HeadroomService.rootUriForTest(URI.create("http://[::1]:8787/v1"));

        assertEquals("http://[::1]:8787/", root.toString());
    }

    @Test
    void probeReceivesConfiguredProxyUriAndHealthTimeout() {
        AtomicReference<URI> seenUri = new AtomicReference<>();
        AtomicReference<Duration> seenTimeout = new AtomicReference<>();

        new HeadroomService(
                true,
                "http://127.0.0.1:8787/v1",
                "https://api.openai.com/v1",
                true,
                false,
                (uri, timeout) -> {
                    seenUri.set(uri);
                    seenTimeout.set(timeout);
                    return HeadroomService.ProbeResult.ok("reachable");
                });

        assertEquals(URI.create("http://127.0.0.1:8787/v1"), seenUri.get());
        assertEquals(Duration.ofMillis(1500), seenTimeout.get());
    }

    @Test
    void blankProxyUrlIsUnavailableAndDoesNotProbe() {
        final boolean[] called = {false};
        HeadroomService service = new HeadroomService(
                true,
                " ",
                "https://api.openai.com/v1",
                true,
                false,
                (uri, timeout) -> {
                    called[0] = true;
                    return HeadroomService.ProbeResult.ok("must not run");
                });

        HeadroomService.Snapshot s = service.snapshot();

        assertEquals("unavailable", s.status());
        assertEquals("https://api.openai.com/v1", s.effectiveBaseUrl());
        assertTrue(s.lastError().contains("Invalid Headroom proxy URL"), s.lastError());
        assertFalse(called[0], "blank Headroom proxy URL must not probe");
    }

    @Test
    void refreshReturnsNewlyComputedSnapshot() {
        AtomicInteger calls = new AtomicInteger();
        HeadroomService service = new HeadroomService(
                true,
                "http://127.0.0.1:8787/v1",
                "https://api.openai.com/v1",
                true,
                false,
                (uri, timeout) -> calls.incrementAndGet() == 1
                        ? HeadroomService.ProbeResult.fail("first")
                        : HeadroomService.ProbeResult.ok("second"));

        assertEquals("fallback", service.snapshot().status());

        HeadroomService.Snapshot refreshed = service.refresh();

        assertEquals("available", refreshed.status());
        assertEquals("available", service.snapshot().status());
    }
}
