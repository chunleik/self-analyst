# Headroom Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `SPEC-HR-*` so SelfAnalyst can route eligible LLM requests through a local Headroom proxy, expose Headroom status/configuration, and document safe development-time memory/failure-learning usage.

**Architecture:** Add Headroom as an optional app-layer service, not as a required dependency or Java-side compressor. `Config` owns durable settings, `HeadroomService` owns health/routing/status, existing LLM clients consume only the effective base URL, and desktop/API surfaces display Headroom state without changing prompt construction or `UsageMeter` semantics.

**Tech Stack:** Java 21, Maven, JUnit 5, Javalin controllers, vanilla ES5 desktop UI, TOML-backed user config, OpenAI-compatible LLM clients through Agentscope.

---

## File Map

- Create: `self-analyst-app/src/main/java/com/selfanalyst/headroom/HeadroomService.java`
  - Owns `SPEC-HR-ARCH-*`, `SPEC-HR-ROUTE-*`, `SPEC-HR-STATS-*`, and safe status snapshots.
- Create: `self-analyst-app/src/test/java/com/selfanalyst/headroom/HeadroomServiceTest.java`
  - Unit tests for disabled, available, fallback, invalid URL, and stats-unavailable behavior.
- Modify: `self-analyst-app/src/main/java/com/selfanalyst/config/Config.java`
  - Adds Headroom record components and loads `headroom.*` properties/env vars.
- Modify: `self-analyst-app/src/main/java/com/selfanalyst/config/SupportedKeys.java`
  - Adds Headroom keys to TOML raw editor whitelist/defaults.
- Modify: `self-analyst-app/src/main/resources/application.properties`
  - Adds documented default Headroom configuration.
- Modify: `self-analyst-app/src/main/java/com/selfanalyst/tools/ConfigTools.java`
  - Adds Headroom keys to allowlist/restart list, displays Headroom section and runtime status.
- Modify: `self-analyst-app/src/main/java/com/selfanalyst/agent/SelfAnalystAgent.java`
  - Uses `HeadroomService.effectiveLlmBaseUrl()` for chat/plain models and passes Headroom runtime status to `ConfigTools`.
- Modify: `self-analyst-app/src/main/java/com/selfanalyst/AppSession.java`
  - Constructs one `HeadroomService` after config load and passes it through app wiring.
- Modify: `self-analyst-app/src/main/java/com/selfanalyst/desktop/DesktopServer.java`
  - Constructor-injects `HeadroomService` into desktop controllers.
- Modify: `self-analyst-app/src/main/java/com/selfanalyst/desktop/controller/DesktopStatusController.java`
  - Includes Headroom status in `/desktop/status` and uses effective LLM base URL for availability checks.
- Modify: `self-analyst-app/src/main/java/com/selfanalyst/desktop/controller/DesktopAgentController.java`
  - Includes Headroom status under `/desktop/usage` while preserving `UsageMeter` fields.
- Modify: `self-analyst-app/src/main/java/com/selfanalyst/desktop/controller/DesktopConfigController.java`
  - Adds structured `headroom` config section and maps `headroom.*` raw/structured keys.
- Modify: `self-analyst-app/src/main/resources/desktop-ui/api.js`
  - Adds `api.getUsage()`.
- Modify: `self-analyst-app/src/main/resources/desktop-ui/state.js`
  - Adds `usage` state.
- Modify: `self-analyst-app/src/main/resources/desktop-ui/ui.js`
  - Loads usage on refresh and displays Headroom status in the LLM status label/tooltip.
- Modify: `self-analyst-app/src/main/resources/desktop-ui/i18n.js`
  - Adds Headroom status labels.
- Modify: `self-analyst-app/src/test/java/com/selfanalyst/config/ConfigTest.java`
  - Verifies defaults and `Config.testDefaults`.
- Modify: `self-analyst-app/src/test/java/com/selfanalyst/desktop/controller/DesktopConfigControllerTest.java`
  - Verifies supported keys and structured Headroom config section.
- Modify: `self-analyst-app/src/test/java/com/selfanalyst/tools/ConfigToolsTest.java`
  - Verifies ConfigTools displays and saves Headroom keys.
- Create: `self-analyst-app/src/test/java/com/selfanalyst/agent/SelfAnalystAgentHeadroomTest.java`
  - Verifies the effective base URL helper without making network calls.
- Create: `self-analyst-app/src/test/java/com/selfanalyst/desktop/controller/DesktopStatusControllerHeadroomTest.java`
  - Verifies `/desktop/status`-level status map through package-visible helpers.
- Create: `docs/headroom.md`
  - Documents user runtime setup and development-time `headroom learn` safety rules.
- Modify: `docs/README.md`
  - Links `docs/headroom.md`.

## Task 1: Add Headroom Configuration Defaults

**Files:**
- Modify: `self-analyst-app/src/main/java/com/selfanalyst/config/Config.java`
- Modify: `self-analyst-app/src/main/java/com/selfanalyst/config/SupportedKeys.java`
- Modify: `self-analyst-app/src/main/resources/application.properties`
- Modify: `self-analyst-app/src/test/java/com/selfanalyst/config/ConfigTest.java`
- Modify: `self-analyst-app/src/test/java/com/selfanalyst/desktop/controller/DesktopConfigControllerTest.java`

- [ ] **Step 1: Add failing config tests**

Append these imports to `ConfigTest.java` if they are missing:

```java
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
```

Append these tests inside `ConfigTest`:

```java
    @Test
    void classpathDefaultsKeepHeadroomOptIn() throws Exception {
        Properties props = new Properties();
        try (var in = Config.class.getClassLoader().getResourceAsStream("application.properties")) {
            assertNotNull(in, "application.properties must be available on the test classpath");
            props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }

        assertEquals("false", props.getProperty("headroom.enabled"));
        assertEquals("http://127.0.0.1:8787/v1", props.getProperty("headroom.proxy-url"));
        assertEquals("true", props.getProperty("headroom.stats.enabled"));
        assertEquals("false", props.getProperty("headroom.output-shaper"));
    }

    @Test
    void exposesHeadroomDefaultsAsConfigValues(@TempDir Path dir) {
        Config cfg = Config.testDefaults(dir);

        assertFalse(cfg.headroomEnabled());
        assertEquals("http://127.0.0.1:8787/v1", cfg.headroomProxyUrl());
        assertTrue(cfg.headroomStatsEnabled());
        assertFalse(cfg.headroomOutputShaper());
    }
```

Update `DesktopConfigControllerTest.supportedKeysIncludeRuntimeConfigKeysAndPrivacyDefaults()` by adding:

```java
        assertEquals("false", defaults.get("headroom.enabled"));
        assertEquals("http://127.0.0.1:8787/v1", defaults.get("headroom.proxy-url"));
        assertEquals("true", defaults.get("headroom.stats.enabled"));
        assertEquals("false", defaults.get("headroom.output-shaper"));
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```powershell
mvn -pl self-analyst-app -Dtest=ConfigTest,DesktopConfigControllerTest test
```

Expected: compilation fails because `Config` has no `headroomEnabled()`, `headroomProxyUrl()`, `headroomStatsEnabled()`, or `headroomOutputShaper()`.

- [ ] **Step 3: Extend `Config` record and loader**

In `Config.java`, add these record components immediately before `String appLanguage`:

```java
        boolean headroomEnabled,
        String headroomProxyUrl,
        boolean headroomStatsEnabled,
        boolean headroomOutputShaper,
```

In `Config.load()`, after the budget parsing block and before web search parsing, insert:

```java
        // ── Headroom proxy integration (SPEC-HR-CFG-001) ──
        boolean headroomEnabled = Boolean.parseBoolean(
                envOrProp(props, "headroom.enabled", "HEADROOM_ENABLED", "false"));
        String headroomProxyUrl = envOrProp(props, "headroom.proxy-url", "HEADROOM_PROXY_URL",
                "http://127.0.0.1:8787/v1");
        boolean headroomStatsEnabled = Boolean.parseBoolean(
                envOrProp(props, "headroom.stats.enabled", "HEADROOM_STATS_ENABLED", "true"));
        boolean headroomOutputShaper = Boolean.parseBoolean(
                envOrProp(props, "headroom.output-shaper", "HEADROOM_OUTPUT_SHAPER", "false"));
```

Update the `return new Config(...)` call by passing these values immediately before `appLanguage`:

```java
                budgetMode, budgetDailyTokens, budgetWarnRatio,
                headroomEnabled, headroomProxyUrl, headroomStatsEnabled, headroomOutputShaper,
                appLanguage);
```

Update `Config.testDefaults(Path baseDir)` by passing these values immediately before `"auto"`:

```java
                2048, 8, 4, "warn", 100000000L, 0.8,
                false, "http://127.0.0.1:8787/v1", true, false,
                "auto");
```

- [ ] **Step 4: Add supported TOML keys**

In `SupportedKeys.java`, after the LLM budget keys and before `agent.summaryRefreshMinutes`, insert:

```java
        put("headroom.enabled", "false", KeyType.BOOLEAN);
        put("headroom.proxy-url", "http://127.0.0.1:8787/v1", KeyType.STRING);
        put("headroom.stats.enabled", "true", KeyType.BOOLEAN);
        put("headroom.output-shaper", "false", KeyType.BOOLEAN);
```

- [ ] **Step 5: Add classpath defaults**

In `application.properties`, after the LLM budget section and before ActivityWatch, insert:

```properties
# Headroom local proxy — see docs/specs/headroom.md
# Optional local-first context compression. Disabled by default; start Headroom yourself
# (for example: headroom proxy --port 8787), then enable this switch.
headroom.enabled=false
headroom.proxy-url=http://127.0.0.1:8787/v1
headroom.stats.enabled=true
headroom.output-shaper=false
```

- [ ] **Step 6: Run tests to verify they pass**

Run:

```powershell
mvn -pl self-analyst-app -Dtest=ConfigTest,DesktopConfigControllerTest test
```

Expected: build succeeds and both test classes pass.

- [ ] **Step 7: Commit Task 1**

Run:

```powershell
git add self-analyst-app/src/main/java/com/selfanalyst/config/Config.java `
        self-analyst-app/src/main/java/com/selfanalyst/config/SupportedKeys.java `
        self-analyst-app/src/main/resources/application.properties `
        self-analyst-app/src/test/java/com/selfanalyst/config/ConfigTest.java `
        self-analyst-app/src/test/java/com/selfanalyst/desktop/controller/DesktopConfigControllerTest.java
git commit -m "feat: add SPEC-HR Headroom config defaults" -m "Co-Authored-By: Codex <codex@openai.com>"
```

Expected: commit succeeds with only `Config.java`, `SupportedKeys.java`, `application.properties`, `ConfigTest.java`, and `DesktopConfigControllerTest.java`.

## Task 2: Add `HeadroomService`

**Files:**
- Create: `self-analyst-app/src/main/java/com/selfanalyst/headroom/HeadroomService.java`
- Create: `self-analyst-app/src/test/java/com/selfanalyst/headroom/HeadroomServiceTest.java`

- [ ] **Step 1: Write the failing service tests**

Create `self-analyst-app/src/test/java/com/selfanalyst/headroom/HeadroomServiceTest.java`:

```java
package com.selfanalyst.headroom;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

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
}
```

- [ ] **Step 2: Run the service tests to verify they fail**

Run:

```powershell
mvn -pl self-analyst-app -Dtest=HeadroomServiceTest test
```

Expected: compilation fails because `HeadroomService` does not exist.

- [ ] **Step 3: Implement `HeadroomService`**

Create `self-analyst-app/src/main/java/com/selfanalyst/headroom/HeadroomService.java`:

```java
package com.selfanalyst.headroom;

import com.selfanalyst.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Optional Headroom proxy integration. The service only resolves routing and safe
 * diagnostics; it never receives prompt bodies or user activity content.
 */
public final class HeadroomService {

    private static final Logger log = LoggerFactory.getLogger(HeadroomService.class);
    private static final Duration HEALTH_TIMEOUT = Duration.ofMillis(1500);

    public interface Probe {
        ProbeResult check(URI proxyUri, Duration timeout);
    }

    public record ProbeResult(boolean ok, String message) {
        public static ProbeResult ok(String message) {
            return new ProbeResult(true, message);
        }

        public static ProbeResult fail(String message) {
            return new ProbeResult(false, message);
        }
    }

    public record Snapshot(
            String status,
            boolean enabled,
            String proxyUrl,
            String originalBaseUrl,
            String effectiveBaseUrl,
            String lastError,
            boolean statsEnabled,
            boolean outputShaper,
            String statsStatus
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", status);
            m.put("enabled", enabled);
            m.put("proxyUrl", proxyUrl);
            m.put("originalBaseUrl", originalBaseUrl);
            m.put("effectiveBaseUrl", effectiveBaseUrl);
            m.put("lastError", lastError);
            m.put("statsEnabled", statsEnabled);
            m.put("outputShaper", outputShaper);
            m.put("statsStatus", statsStatus);
            return m;
        }
    }

    private final boolean enabled;
    private final String proxyUrl;
    private final String originalBaseUrl;
    private final boolean statsEnabled;
    private final boolean outputShaper;
    private final Probe probe;
    private volatile Snapshot snapshot;

    public static HeadroomService fromConfig(Config config) {
        Objects.requireNonNull(config, "config");
        return new HeadroomService(
                config.headroomEnabled(),
                config.headroomProxyUrl(),
                config.llmBaseUrl(),
                config.headroomStatsEnabled(),
                config.headroomOutputShaper(),
                HeadroomService::defaultProbe);
    }

    public HeadroomService(boolean enabled,
                           String proxyUrl,
                           String originalBaseUrl,
                           boolean statsEnabled,
                           boolean outputShaper,
                           Probe probe) {
        this.enabled = enabled;
        this.proxyUrl = safeString(proxyUrl);
        this.originalBaseUrl = safeString(originalBaseUrl);
        this.statsEnabled = statsEnabled;
        this.outputShaper = outputShaper;
        this.probe = probe != null ? probe : HeadroomService::defaultProbe;
        this.snapshot = resolve();
    }

    public Snapshot refresh() {
        this.snapshot = resolve();
        return snapshot;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public String effectiveLlmBaseUrl() {
        return snapshot.effectiveBaseUrl();
    }

    public String runtimeStatusLine() {
        Snapshot s = snapshot();
        if (!s.enabled()) {
            return "disabled";
        }
        String msg = s.status();
        if (s.lastError() != null && !s.lastError().isBlank()) {
            msg += " (" + s.lastError() + ")";
        }
        return msg;
    }

    private Snapshot resolve() {
        if (!enabled) {
            return new Snapshot("disabled", false, proxyUrl, originalBaseUrl, originalBaseUrl,
                    "", statsEnabled, outputShaper, statsEnabled ? "disabled" : "disabled");
        }
        URI uri;
        try {
            uri = URI.create(proxyUrl);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("missing scheme or host");
            }
        } catch (RuntimeException e) {
            String msg = "Invalid Headroom proxy URL: " + e.getMessage();
            log.warn("Headroom unavailable: {}", msg);
            return new Snapshot("unavailable", true, proxyUrl, originalBaseUrl, originalBaseUrl,
                    msg, statsEnabled, outputShaper, statsEnabled ? "unavailable" : "disabled");
        }

        ProbeResult result;
        try {
            result = probe.check(uri, HEALTH_TIMEOUT);
        } catch (RuntimeException e) {
            result = ProbeResult.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (result.ok()) {
            return new Snapshot("available", true, proxyUrl, originalBaseUrl, proxyUrl,
                    "", statsEnabled, outputShaper, statsEnabled ? "unknown" : "disabled");
        }
        String msg = result.message() == null || result.message().isBlank()
                ? "Headroom proxy is not reachable"
                : result.message();
        log.warn("Headroom proxy unavailable; falling back to llm.base-url: {}", msg);
        return new Snapshot("fallback", true, proxyUrl, originalBaseUrl, originalBaseUrl,
                msg, statsEnabled, outputShaper, statsEnabled ? "unavailable" : "disabled");
    }

    private static ProbeResult defaultProbe(URI proxyUri, Duration timeout) {
        try {
            URI root = rootUri(proxyUri);
            HttpRequest req = HttpRequest.newBuilder(root)
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(timeout)
                    .build();
            HttpResponse<Void> response = client.send(req, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 100 && status < 500) {
                return ProbeResult.ok("HTTP " + status);
            }
            return ProbeResult.fail("HTTP " + status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProbeResult.fail("interrupted");
        } catch (Exception e) {
            return ProbeResult.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static URI rootUri(URI proxyUri) {
        String scheme = proxyUri.getScheme().toLowerCase(Locale.ROOT);
        int port = proxyUri.getPort();
        String authority = port >= 0 ? proxyUri.getHost() + ":" + port : proxyUri.getHost();
        return URI.create(scheme + "://" + authority + "/");
    }

    private static String safeString(String value) {
        return value == null ? "" : value.trim();
    }
}
```

- [ ] **Step 4: Run the service tests to verify they pass**

Run:

```powershell
mvn -pl self-analyst-app -Dtest=HeadroomServiceTest test
```

Expected: all `HeadroomServiceTest` tests pass.

- [ ] **Step 5: Commit Task 2**

Run:

```powershell
git add self-analyst-app/src/main/java/com/selfanalyst/headroom/HeadroomService.java `
        self-analyst-app/src/test/java/com/selfanalyst/headroom/HeadroomServiceTest.java
git commit -m "feat: add SPEC-HR Headroom service" -m "Co-Authored-By: Codex <codex@openai.com>"
```

Expected: commit succeeds with only the service and service test.

## Task 3: Wire Headroom Into LLM Construction And Backend Status

**Files:**
- Modify: `self-analyst-app/src/main/java/com/selfanalyst/agent/SelfAnalystAgent.java`
- Modify: `self-analyst-app/src/main/java/com/selfanalyst/AppSession.java`
- Modify: `self-analyst-app/src/main/java/com/selfanalyst/desktop/DesktopServer.java`
- Modify: `self-analyst-app/src/main/java/com/selfanalyst/desktop/controller/DesktopStatusController.java`
- Modify: `self-analyst-app/src/main/java/com/selfanalyst/desktop/controller/DesktopAgentController.java`
- Create: `self-analyst-app/src/test/java/com/selfanalyst/agent/SelfAnalystAgentHeadroomTest.java`
- Create: `self-analyst-app/src/test/java/com/selfanalyst/desktop/controller/DesktopStatusControllerHeadroomTest.java`
- Modify: `self-analyst-app/src/test/java/com/selfanalyst/desktop/controller/DesktopAgentControllerTest.java`

- [ ] **Step 1: Write failing agent routing tests**

Create `self-analyst-app/src/test/java/com/selfanalyst/agent/SelfAnalystAgentHeadroomTest.java`:

```java
package com.selfanalyst.agent;

import com.selfanalyst.config.Config;
import com.selfanalyst.headroom.HeadroomService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SelfAnalystAgentHeadroomTest {

    @Test
    void effectiveBaseUrlUsesConfigWhenHeadroomMissing(@TempDir Path dir) {
        Config config = Config.testDefaults(dir);

        assertEquals(config.llmBaseUrl(), SelfAnalystAgent.effectiveLlmBaseUrl(config, null));
    }

    @Test
    void effectiveBaseUrlUsesHeadroomSnapshotWhenPresent(@TempDir Path dir) {
        Config config = Config.testDefaults(dir);
        HeadroomService service = new HeadroomService(
                true,
                "http://127.0.0.1:8787/v1",
                config.llmBaseUrl(),
                true,
                false,
                (uri, timeout) -> HeadroomService.ProbeResult.ok("reachable"));

        assertEquals("http://127.0.0.1:8787/v1",
                SelfAnalystAgent.effectiveLlmBaseUrl(config, service));
    }
}
```

- [ ] **Step 2: Write failing status/controller tests**

Create `self-analyst-app/src/test/java/com/selfanalyst/desktop/controller/DesktopStatusControllerHeadroomTest.java`:

```java
package com.selfanalyst.desktop.controller;

import com.selfanalyst.config.Config;
import com.selfanalyst.headroom.HeadroomService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopStatusControllerHeadroomTest {

    @Test
    void llmStatusUsesEffectiveHeadroomBaseUrl(@TempDir Path dir) {
        Config config = Config.testDefaults(dir);
        HeadroomService service = new HeadroomService(
                true,
                "http://127.0.0.1:8787/v1",
                config.llmBaseUrl(),
                true,
                false,
                (uri, timeout) -> HeadroomService.ProbeResult.ok("reachable"));
        DesktopStatusController ctrl = new DesktopStatusController(config, null, null, null, service);

        Map<String, Object> llm = ctrl.buildLlmStatus(false);
        Map<String, Object> headroom = ctrl.buildHeadroomStatus();

        assertEquals("http://127.0.0.1:8787/v1", llm.get("baseUrl"));
        assertEquals("available", headroom.get("status"));
        assertEquals(true, headroom.get("enabled"));
    }
}
```

Append this test to `DesktopAgentControllerTest`:

```java
    @Test
    void usageSnapshotIncludesHeadroomWhenAgentMissing(@TempDir java.nio.file.Path dir) {
        var config = com.selfanalyst.config.Config.testDefaults(dir);
        var headroom = new com.selfanalyst.headroom.HeadroomService(
                true,
                "http://127.0.0.1:8787/v1",
                config.llmBaseUrl(),
                true,
                false,
                (uri, timeout) -> com.selfanalyst.headroom.HeadroomService.ProbeResult.ok("reachable"));
        var ctrl = new DesktopAgentController(null, null, null, null, config, headroom);

        var usage = ctrl.usagePayload();

        assertEquals("off", usage.get("mode"));
        assertTrue(usage.containsKey("headroom"));
    }
```

Add these imports to `DesktopAgentControllerTest` if they are missing:

```java
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
```

- [ ] **Step 3: Run tests to verify they fail**

Run:

```powershell
mvn -pl self-analyst-app -Dtest=SelfAnalystAgentHeadroomTest,DesktopStatusControllerHeadroomTest,DesktopAgentControllerTest test
```

Expected: compilation fails because constructors/helpers with Headroom arguments do not exist yet.

- [ ] **Step 4: Update `SelfAnalystAgent`**

In `SelfAnalystAgent.java`, add:

```java
import com.selfanalyst.headroom.HeadroomService;
```

Add a field near `usageMeter`:

```java
    private final HeadroomService headroomService;
```

Change the existing constructor ending in `Supplier<String> audioRuntimeStatusSupplier` to delegate:

```java
    public SelfAnalystAgent(Config config, WikiStore wikiStore, WikiTools wikiTools,
                             UserConfigStore userConfigStore, FileTools fileTools,
                             UsageMeter usageMeter,
                             Supplier<String> audioRuntimeStatusSupplier) throws IOException {
        this(config, wikiStore, wikiTools, userConfigStore, fileTools, usageMeter,
                audioRuntimeStatusSupplier, null);
    }
```

Add the new constructor below it:

```java
    public SelfAnalystAgent(Config config, WikiStore wikiStore, WikiTools wikiTools,
                             UserConfigStore userConfigStore, FileTools fileTools,
                             UsageMeter usageMeter,
                             Supplier<String> audioRuntimeStatusSupplier,
                             HeadroomService headroomService) throws IOException {
        this.usageMeter = usageMeter;
        this.headroomService = headroomService;
        this.lang = config.effectiveLanguage();
        this.wikiStore = wikiStore;
        this.semanticEnabled = wikiTools != null && wikiTools.hasSemanticIndex();
        this.hasConfigTools = userConfigStore != null;
        this.hasFileTools = fileTools != null;
        this.memory = MemoryStore.load(config.memoryDir());
        this.tools = new ActivityWatchTools(config.awBaseUrl(), config.awTimeout());

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);
        if (wikiTools != null) {
            toolkit.registerTool(wikiTools);
        }
        if (fileTools != null) {
            toolkit.registerTool(fileTools);
        }
        if (userConfigStore != null) {
            toolkit.registerTool(new ConfigTools(userConfigStore, audioRuntimeStatusSupplier,
                    headroomService != null ? headroomService::runtimeStatusLine : null));
        }
        registerWebSearchMcp(toolkit, config);

        Integer maxTokens = config.llmMaxTokens() > 0 ? config.llmMaxTokens() : null;
        String llmBaseUrl = effectiveLlmBaseUrl(config, headroomService);

        GenerateOptions.Builder chatOpts = GenerateOptions.builder()
                .temperature(config.llmTemperature());
        if (maxTokens != null) chatOpts.maxTokens(maxTokens);
        OpenAIChatModel chatModel = OpenAIChatModel.builder()
                .apiKey(config.llmApiKey())
                .modelName(config.llmModel())
                .baseUrl(llmBaseUrl)
                .generateOptions(chatOpts.build())
                .build();

        GenerateOptions.Builder plainOpts = GenerateOptions.builder()
                .temperature(0.2)
                .stream(false);
        if (maxTokens != null) plainOpts.maxTokens(maxTokens);
        this.plainModel = OpenAIChatModel.builder()
                .apiKey(config.llmApiKey())
                .modelName(config.llmModel())
                .baseUrl(llmBaseUrl)
                .stream(false)
                .generateOptions(plainOpts.build())
                .build();

        this.agent = ReActAgent.builder()
                .name("SelfAnalyst")
                .sysPrompt(buildSystemPrompt())
                .model(chatModel)
                .toolkit(toolkit)
                .hook(new DynamicMemoryContextHook(lang, () -> memory.profile().buildContextSummary()))
                .hook(new PlanHook(usageMeter))
                .maxIters(config.agentMaxIters())
                .modelExecutionConfig(ExecutionConfig.builder()
                        .timeout(Duration.ofSeconds(45))
                        .maxAttempts(1)
                        .build())
                .build();
    }
```

Add this package-visible helper near `chat()`:

```java
    static String effectiveLlmBaseUrl(Config config, HeadroomService headroomService) {
        return headroomService != null ? headroomService.effectiveLlmBaseUrl() : config.llmBaseUrl();
    }
```

Remove the now-duplicated body from the old constructor so only the new constructor contains initialization logic.

- [ ] **Step 5: Wire `AppSession` and `DesktopServer`**

In `AppSession.java`, add:

```java
import com.selfanalyst.headroom.HeadroomService;
```

Add a field:

```java
    private final HeadroomService headroomService;
```

After `this.usageMeter = new UsageMeter(config, config.memoryDir());`, insert:

```java
        this.headroomService = HeadroomService.fromConfig(config);
```

Change the `SelfAnalystAgent` construction to:

```java
            a = new SelfAnalystAgent(config, wikiStore, wikiTools, userConfigStore, fileTools,
                    usageMeter, audioRuntimeStatus, headroomService);
```

Change the `DesktopServer` construction to:

```java
            desktopServer = new DesktopServer(awServer.app(), config, agent,
                    awServer.eventStore(), awServer.bucketStore(), memoryStore,
                    watcherManager, contentWatcher, audioCaptureManager, headroomService);
```

In `DesktopServer.java`, import:

```java
import com.selfanalyst.headroom.HeadroomService;
```

Change the full constructor signature by adding `HeadroomService headroomService` after `AudioCaptureManager audioCaptureManager`. Keep the older constructor delegating with `null`:

```java
        this(app, config, agent, eventStore, null, memoryStore,
                watcherManager, contentWatcher, audioCaptureManager, null);
```

Update controller construction:

```java
        this.agentCtrl = new DesktopAgentController(summaryService, adviceService, agent, taskStore, config,
                headroomService);
        this.configCtrl = new DesktopConfigController(config, userConfigStore, headroomService);
        this.statusCtrl = new DesktopStatusController(
                config, watcherManager, contentWatcher, audioCaptureManager, headroomService);
```

- [ ] **Step 6: Update desktop controllers**

In `DesktopStatusController.java`, import `HeadroomService`, add a field, and keep the old constructor delegating:

```java
    private final HeadroomService headroomService;

    public DesktopStatusController(Config config,
                                   WatcherManager watcherManager,
                                   ContentWatcher contentWatcher,
                                   AudioCaptureManager audioCaptureManager) {
        this(config, watcherManager, contentWatcher, audioCaptureManager, null);
    }

    public DesktopStatusController(Config config,
                                   WatcherManager watcherManager,
                                   ContentWatcher contentWatcher,
                                   AudioCaptureManager audioCaptureManager,
                                   HeadroomService headroomService) {
        this.config = config;
        this.watcherManager = watcherManager;
        this.contentWatcher = contentWatcher;
        this.audioCaptureManager = audioCaptureManager;
        this.headroomService = headroomService;
        llmChecker.scheduleAtFixedRate(this::refreshLlmAvailability, 0, 60, TimeUnit.SECONDS);
    }
```

In `getStatus`, replace the inline LLM map block with:

```java
        status.put("llm", buildLlmStatus(llmAvailableCache.get()));
        status.put("headroom", buildHeadroomStatus());
```

Add helpers:

```java
    Map<String, Object> buildLlmStatus(boolean available) {
        Map<String, Object> llm = new LinkedHashMap<>();
        boolean configured = config.llmApiKey() != null
                && !config.llmApiKey().isBlank()
                && !config.llmApiKey().contains("CHANGE_ME");
        llm.put("configured", configured);
        llm.put("available", available);
        llm.put("model", config.llmModel());
        llm.put("baseUrl", headroomService != null ? headroomService.effectiveLlmBaseUrl() : config.llmBaseUrl());
        return llm;
    }

    Map<String, Object> buildHeadroomStatus() {
        return headroomService != null
                ? headroomService.snapshot().toMap()
                : Map.of("status", "disabled", "enabled", false);
    }
```

In `doCheckLlmAvailability()`, replace `String baseUrl = config.llmBaseUrl();` with:

```java
            String baseUrl = headroomService != null ? headroomService.effectiveLlmBaseUrl() : config.llmBaseUrl();
```

In `DesktopAgentController.java`, import `HeadroomService`, add a field, keep the old constructor delegating, and add a new constructor:

```java
    private final HeadroomService headroomService;

    public DesktopAgentController(SummaryService summaryService,
                                  BehaviorAdviceService adviceService,
                                  SelfAnalystAgent agent,
                                  TaskStore taskStore,
                                  Config config) {
        this(summaryService, adviceService, agent, taskStore, config, null);
    }

    public DesktopAgentController(SummaryService summaryService,
                                  BehaviorAdviceService adviceService,
                                  SelfAnalystAgent agent,
                                  TaskStore taskStore,
                                  Config config,
                                  HeadroomService headroomService) {
        this.summaryService = summaryService;
        this.adviceService = adviceService;
        this.promptService = new SummaryPromptService();
        this.agent = agent;
        this.taskStore = taskStore;
        this.config = config;
        this.headroomService = headroomService;
    }
```

Replace `getUsage` with:

```java
    public void getUsage(Context ctx) {
        ctx.json(usagePayload());
    }

    Map<String, Object> usagePayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (agent == null) {
            payload.put("mode", "off");
            payload.put("status", "ok");
        } else {
            payload.putAll(agent.usageSnapshot());
        }
        if (headroomService != null) {
            payload.put("headroom", headroomService.snapshot().toMap());
        }
        return payload;
    }
```

- [ ] **Step 7: Run backend wiring tests**

Run:

```powershell
mvn -pl self-analyst-app -Dtest=SelfAnalystAgentHeadroomTest,DesktopStatusControllerHeadroomTest,DesktopAgentControllerTest test
```

Expected: all selected tests pass.

- [ ] **Step 8: Commit Task 3**

Run:

```powershell
git add self-analyst-app/src/main/java/com/selfanalyst/agent/SelfAnalystAgent.java `
        self-analyst-app/src/main/java/com/selfanalyst/AppSession.java `
        self-analyst-app/src/main/java/com/selfanalyst/desktop/DesktopServer.java `
        self-analyst-app/src/main/java/com/selfanalyst/desktop/controller/DesktopStatusController.java `
        self-analyst-app/src/main/java/com/selfanalyst/desktop/controller/DesktopAgentController.java `
        self-analyst-app/src/test/java/com/selfanalyst/agent/SelfAnalystAgentHeadroomTest.java `
        self-analyst-app/src/test/java/com/selfanalyst/desktop/controller/DesktopStatusControllerHeadroomTest.java `
        self-analyst-app/src/test/java/com/selfanalyst/desktop/controller/DesktopAgentControllerTest.java
git commit -m "feat: route LLM calls through SPEC-HR Headroom service" -m "Co-Authored-By: Codex <codex@openai.com>"
```

Expected: commit succeeds with only backend wiring files and tests.

## Task 4: Expose Headroom In Config Tools, Config API, And Desktop Status UI

**Files:**
- Modify: `self-analyst-app/src/main/java/com/selfanalyst/tools/ConfigTools.java`
- Modify: `self-analyst-app/src/main/java/com/selfanalyst/desktop/controller/DesktopConfigController.java`
- Modify: `self-analyst-app/src/main/resources/desktop-ui/api.js`
- Modify: `self-analyst-app/src/main/resources/desktop-ui/state.js`
- Modify: `self-analyst-app/src/main/resources/desktop-ui/ui.js`
- Modify: `self-analyst-app/src/main/resources/desktop-ui/i18n.js`
- Modify: `self-analyst-app/src/test/java/com/selfanalyst/tools/ConfigToolsTest.java`
- Modify: `self-analyst-app/src/test/java/com/selfanalyst/desktop/controller/DesktopConfigControllerTest.java`

- [ ] **Step 1: Add failing `ConfigTools` tests**

Append this test to `ConfigToolsTest`:

```java
    @Test
    void getConfigIncludesHeadroomSectionAndRuntimeStatus(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        store.saveRaw("[headroom]\nenabled = true\nproxy-url = \"http://127.0.0.1:8787/v1\"\n");

        String config = new ConfigTools(store, () -> "disabled", () -> "available").getConfig();

        assertTrue(config.contains("[Headroom]"), config);
        assertTrue(config.contains("headroom.enabled = true"), config);
        assertTrue(config.contains("headroom.proxy-url = http://127.0.0.1:8787/v1"), config);
        assertTrue(config.contains("headroom.runtimeStatus = available"), config);
    }

    @Test
    void setConfigValueAllowsHeadroomKeys(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);

        String result = new ConfigTools(store).setConfigValue("headroom.enabled", "true");

        assertTrue(result.contains("配置已保存"), result);
        assertTrue(result.contains("需重启"), result);
    }
```

- [ ] **Step 2: Add failing desktop config tests**

Append this test to `DesktopConfigControllerTest`:

```java
    @Test
    @SuppressWarnings("unchecked")
    void structuredConfigIncludesHeadroomSection(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        store.saveRaw("[headroom]\nenabled = true\nproxy-url = \"http://127.0.0.1:8787/v1\"\n");
        var ctrl = controller(dir, store);
        Properties effective = store.load();

        Map<String, Map<String, Object>> headroom = invokeSection(ctrl, "buildHeadroomSection", effective);

        assertEquals("true", headroom.get("headroomEnabled").get("effectiveValue"));
        assertEquals("http://127.0.0.1:8787/v1", headroom.get("headroomProxyUrl").get("effectiveValue"));
    }
```

Extend `runtimeConfigKeysAreNotReportedUnknown()` text by adding:

```java
                + "[headroom]\n"
                + "enabled = false\n"
                + "proxy-url = \"http://127.0.0.1:8787/v1\"\n"
                + "stats.enabled = true\n"
                + "output-shaper = false\n"
```

- [ ] **Step 3: Run tests to verify they fail**

Run:

```powershell
mvn -pl self-analyst-app -Dtest=ConfigToolsTest,DesktopConfigControllerTest test
```

Expected: compilation or assertion failure because Headroom keys are not exposed yet.

- [ ] **Step 4: Update `ConfigTools`**

In `ConfigTools.java`, add Headroom keys to `ALLOWED_KEYS`:

```java
            "headroom.enabled", "headroom.proxy-url",
            "headroom.stats.enabled", "headroom.output-shaper",
```

Add Headroom keys to `RESTART_REQUIRED`:

```java
            "headroom.enabled", "headroom.proxy-url",
            "headroom.stats.enabled", "headroom.output-shaper",
```

Add a field:

```java
    private final Supplier<String> headroomRuntimeStatusSupplier;
```

Replace constructors with:

```java
    public ConfigTools(UserConfigStore userStore) {
        this(userStore, null, null);
    }

    public ConfigTools(UserConfigStore userStore, Supplier<String> audioRuntimeStatusSupplier) {
        this(userStore, audioRuntimeStatusSupplier, null);
    }

    public ConfigTools(UserConfigStore userStore,
                       Supplier<String> audioRuntimeStatusSupplier,
                       Supplier<String> headroomRuntimeStatusSupplier) {
        this.userStore = userStore;
        this.audioRuntimeStatusSupplier = audioRuntimeStatusSupplier;
        this.headroomRuntimeStatusSupplier = headroomRuntimeStatusSupplier;
    }
```

In the tool description string, add:

```java
            "headroom.enabled、headroom.proxy-url、headroom.stats.enabled、headroom.output-shaper；" +
```

After the `[LLM]` section in `getConfig()`, append:

```java
        appendSection(sb, "Headroom", new String[][]{
                {"headroom.enabled",       eff.getProperty("headroom.enabled",       "false"), null},
                {"headroom.proxy-url",     eff.getProperty("headroom.proxy-url",     "http://127.0.0.1:8787/v1"), null},
                {"headroom.stats.enabled", eff.getProperty("headroom.stats.enabled", "true"),  null},
                {"headroom.output-shaper", eff.getProperty("headroom.output-shaper", "false"), null},
        });
```

In the runtime status block, include Headroom when available:

```java
        String headroomRuntimeStatus = headroomRuntimeStatus();
        if (audioRuntimeStatus != null || headroomRuntimeStatus != null) {
            java.util.List<String[]> rows = new java.util.ArrayList<>();
            if (audioRuntimeStatus != null) {
                rows.add(new String[]{"aw.audio.runtimeStatus", audioRuntimeStatus, null});
            }
            if (headroomRuntimeStatus != null) {
                rows.add(new String[]{"headroom.runtimeStatus", headroomRuntimeStatus, null});
            }
            appendSection(sb, "运行时状态", rows.toArray(new String[0][]));
        }
```

Replace the old single-audio runtime block with the multi-row runtime status block shown in this step, then add:

```java
    private String headroomRuntimeStatus() {
        if (headroomRuntimeStatusSupplier == null) return null;
        try {
            String status = headroomRuntimeStatusSupplier.get();
            return status == null || status.isBlank() ? "unknown" : status;
        } catch (Exception e) {
            return "unknown";
        }
    }
```

- [ ] **Step 5: Update `DesktopConfigController` structured config**

Add Headroom keys to `RESTART_REQUIRED`:

```java
            "headroom.enabled", "headroom.proxy-url",
            "headroom.stats.enabled", "headroom.output-shaper",
```

Add a `HeadroomService` field and constructor overload:

```java
    private final com.selfanalyst.headroom.HeadroomService headroomService;

    public DesktopConfigController(Config config, UserConfigStore userStore) {
        this(config, userStore, null);
    }

    public DesktopConfigController(Config config,
                                   UserConfigStore userStore,
                                   com.selfanalyst.headroom.HeadroomService headroomService) {
        this.config = config;
        this.userStore = userStore;
        this.headroomService = headroomService;
        this.historyStore = new ConfigHistoryStore(userStore.filePath().getParent());
    }
```

In `getConfig`, add:

```java
        response.put("headroom", buildHeadroomSection(defaults));
```

In `putConfig`, add mappings:

```java
            keyMapping.put("headroomEnabled", "headroom.enabled");
            keyMapping.put("headroomProxyUrl", "headroom.proxy-url");
            keyMapping.put("headroomStatsEnabled", "headroom.stats.enabled");
            keyMapping.put("headroomOutputShaper", "headroom.output-shaper");
```

Add `headroom` to `sectionToPrefix`. Use this `LinkedHashMap` block so the method does not hit the `Map.of(...)` 10-entry limit:

```java
            Map<String, String> sectionToPrefix = new LinkedHashMap<>();
            sectionToPrefix.put("llm", "llm.");
            sectionToPrefix.put("aw", "aw.");
            sectionToPrefix.put("collection", "aw.collection.");
            sectionToPrefix.put("audio", "aw.audio.");
            sectionToPrefix.put("agent", "agent.");
            sectionToPrefix.put("desktop", "desktop.");
            sectionToPrefix.put("embedding", "embedding.");
            sectionToPrefix.put("websearch", "websearch.");
            sectionToPrefix.put("headroom", "headroom.");
```

Add the builder near `buildWebSearchSection`:

```java
    private Map<String, Map<String, Object>> buildHeadroomSection(Properties eff) {
        var m = new LinkedHashMap<String, Map<String, Object>>();
        m.put("headroomEnabled", field("headroom.enabled", eff.getProperty("headroom.enabled", "false")));
        m.put("headroomProxyUrl", field("headroom.proxy-url", eff.getProperty("headroom.proxy-url", "http://127.0.0.1:8787/v1")));
        m.put("headroomStatsEnabled", field("headroom.stats.enabled", eff.getProperty("headroom.stats.enabled", "true")));
        m.put("headroomOutputShaper", field("headroom.output-shaper", eff.getProperty("headroom.output-shaper", "false")));
        if (headroomService != null) {
            m.put("headroomStatus", field("headroom.runtimeStatus", headroomService.runtimeStatusLine()));
        }
        return m;
    }
```

Update `findUserValue` prefixes:

```java
        String[] prefixes = {"llm.", "aw.", "aw.collection.", "aw.audio.", "agent.", "desktop.", "embedding.", "websearch.", "headroom."};
```

- [ ] **Step 6: Update desktop UI state/status display**

In `api.js`, add:

```javascript
  getUsage: function () {
    return fetch(API_BASE + "/desktop/usage").then(function (r) {
      if (!r.ok) throw new Error("Usage fetch failed: " + r.status);
      return r.json();
    });
  },
```

Place it after `getSummary`.

In `state.js`, add:

```javascript
  usage: null,
```

after `summary: null,`.

In `ui.js`, update the LLM section of `updateStatusBar()`:

```javascript
  var llmOk = st.llm && st.llm.configured;
  var headroom = st.headroom || (state.usage && state.usage.headroom) || null;
  var llmLabel = t("status.llm");
  var llmTitle = llmLabel + " " + (llmOk ? t("status.ok") : t("status.notReady"));
  if (headroom && headroom.enabled) {
    var hrStatus = headroom.status || "unknown";
    llmLabel += " · HR " + t("headroom.status." + hrStatus);
    llmTitle += " · Headroom " + t("headroom.status." + hrStatus);
    if (headroom.lastError) {
      llmTitle += ": " + headroom.lastError;
    }
  }
  setStatusDot(state.dom.llmDot, llmOk, t("status.llm"));
  state.dom.llmDot.title = llmTitle;
  state.dom.llmText.textContent = llmLabel;
```

Leave `setStatusDot` unchanged. The LLM dot title is intentionally overwritten after the shared helper runs so no other status-dot caller changes behavior.

```javascript
function setStatusDot(el, ok, label) {
  el.className = "status-dot " + (ok ? "green" : "orange");
  el.title = label + " " + (ok ? t("status.ok") : t("status.notReady"));
}
```

In `loadAll()`, include usage in phase 1:

```javascript
  Promise.all([
    withTimeout(api.getStatus(), 5000).catch(function () { return null; }),
    withTimeout(api.getTasks(), 5000).catch(function () { return []; }),
    withTimeout(api.getUsage(), 5000).catch(function () { return null; })
  ]).then(function (results) {
    state.status = results[0];
    state.tasks = results[1] || [];
    state.usage = results[2];
```

In `startAutoRefresh()`, include usage:

```javascript
    Promise.all([
      api.getStatus().catch(function () { return state.status; }),
      api.getTasks().catch(function () { return state.tasks; }),
      api.getUsage().catch(function () { return state.usage; }),
    ]).then(function (results) {
      state.status = results[0];
      state.tasks = results[1] || state.tasks;
      state.usage = results[2] || state.usage;
      updateStatusBar();
      renderTasks();
    });
```

In `i18n.js`, add near status labels:

```javascript
  "headroom.status.disabled": { zh: "关闭", en: "off" },
  "headroom.status.available": { zh: "可用", en: "available" },
  "headroom.status.fallback": { zh: "回退", en: "fallback" },
  "headroom.status.unavailable": { zh: "不可用", en: "unavailable" },
  "headroom.status.unknown": { zh: "未知", en: "unknown" },
```

- [ ] **Step 7: Run config and frontend-adjacent tests**

Run:

```powershell
mvn -pl self-analyst-app -Dtest=ConfigToolsTest,DesktopConfigControllerTest,DesktopAgentControllerTest,DesktopStatusControllerHeadroomTest test
```

Expected: all selected tests pass.

- [ ] **Step 8: Commit Task 4**

Run:

```powershell
git add self-analyst-app/src/main/java/com/selfanalyst/tools/ConfigTools.java `
        self-analyst-app/src/main/java/com/selfanalyst/desktop/controller/DesktopConfigController.java `
        self-analyst-app/src/main/resources/desktop-ui/api.js `
        self-analyst-app/src/main/resources/desktop-ui/state.js `
        self-analyst-app/src/main/resources/desktop-ui/ui.js `
        self-analyst-app/src/main/resources/desktop-ui/i18n.js `
        self-analyst-app/src/test/java/com/selfanalyst/tools/ConfigToolsTest.java `
        self-analyst-app/src/test/java/com/selfanalyst/desktop/controller/DesktopConfigControllerTest.java
git commit -m "feat: expose SPEC-HR Headroom status and config" -m "Co-Authored-By: Codex <codex@openai.com>"
```

Expected: commit succeeds with config/tool/UI visibility changes.

## Task 5: Document Headroom Runtime And Development Workflows

**Files:**
- Create: `docs/headroom.md`
- Modify: `docs/README.md`
- Modify: `docs/specs/headroom.md`

- [ ] **Step 1: Write the development workflow documentation**

Create `docs/headroom.md`:

```markdown
# Headroom

SelfAnalyst can optionally use [Headroom](https://github.com/headroomlabs-ai/headroom)
as a local OpenAI-compatible proxy for LLM context compression. This integration
is disabled by default and is not required to run the app.

## Runtime Token Reduction

Start Headroom yourself:

```powershell
headroom proxy --port 8787
```

Then set:

```toml
[headroom]
enabled = true
proxy-url = "http://127.0.0.1:8787/v1"
stats.enabled = true
output-shaper = false
```

Restart the SelfAnalyst backend after changing these values. When the proxy is
reachable, Agent chat and plain summary completions use the Headroom base URL.
When the proxy is not reachable, SelfAnalyst falls back to `llm.base-url`.

Embedding and speech-to-text audio upload requests do not go through Headroom in
the first version.

## Development Memory And Failure Learning

Headroom memory/failure learning is for repository development context only. It
can store stable project facts such as module paths, build commands, CodeGraph
usage expectations, and repeatable fixes learned from failed agent sessions.

Preview learning suggestions:

```powershell
headroom learn
```

Apply suggestions only after review:

```powershell
headroom learn --apply
```

Durable team-wide rules may be copied into `AGENTS.md` or `CLAUDE.md` only when
they are stable, non-sensitive, and useful to all agents. Keep machine-specific
or personal preferences in a local ignored file.

Never store these in Headroom development memory:

- SelfAnalyst user activity data
- OCR/UIA screen text
- audio transcripts
- API keys, tokens, or passwords
- private file contents
```

- [ ] **Step 2: Update documentation index**

In `docs/README.md`, add under Architecture or Specifications:

```markdown
- [headroom.md](headroom.md) — Runtime Headroom proxy setup plus development memory/failure-learning safety guide
```

- [ ] **Step 3: Update SDD traceability for docs**

In `docs/specs/headroom.md`, update the `SPEC-HR-DEV-*` traceability row to include `docs/headroom.md`:

```markdown
| SPEC-HR-DEV-* | `docs/headroom.md`, `AGENTS.md`, `CLAUDE.md`, docs | 文档审查 |
```

- [ ] **Step 4: Verify docs contain the required safety language**

Run:

```powershell
rg -n "headroom learn|Never store|audio transcripts|OCR/UIA|proxy --port 8787" docs/headroom.md docs/specs/headroom.md
```

Expected: output includes the runtime proxy command, `headroom learn`, and the forbidden sensitive data categories.

- [ ] **Step 5: Commit Task 5**

Run:

```powershell
git add docs/headroom.md docs/README.md docs/specs/headroom.md
git commit -m "docs: add SPEC-HR Headroom workflow guide" -m "Co-Authored-By: Codex <codex@openai.com>"
```

Expected: commit succeeds with only documentation changes.

## Task 6: Final Verification

**Files:**
- Verify all files changed by Tasks 1-5.

- [ ] **Step 1: Run focused Headroom tests**

Run:

```powershell
mvn -pl self-analyst-app -Dtest=HeadroomServiceTest,SelfAnalystAgentHeadroomTest,DesktopStatusControllerHeadroomTest,DesktopAgentControllerTest,ConfigToolsTest,ConfigTest,DesktopConfigControllerTest test
```

Expected: all selected tests pass.

- [ ] **Step 2: Run app module test suite**

Run:

```powershell
mvn -pl self-analyst-app test
```

Expected: Maven exits with code 0.

- [ ] **Step 3: Run compile with upstream modules**

Run:

```powershell
mvn -pl self-analyst-app -am compile
```

Expected: Maven exits with code 0.

- [ ] **Step 4: Verify no accidental prompt/content logging**

Run:

```powershell
rg -n "prompt|OCR|transcript|api-key|Authorization|headroom" self-analyst-app/src/main/java/com/selfanalyst/headroom self-analyst-app/src/main/java/com/selfanalyst/desktop/controller self-analyst-app/src/main/java/com/selfanalyst/agent/SelfAnalystAgent.java
```

Expected: any matches are limited to safe key names, status labels, comments, or existing Authorization code outside Headroom diagnostics. No code logs prompt bodies, OCR text, audio transcripts, or API key values as part of Headroom diagnostics.

- [ ] **Step 5: Inspect final diff**

Run:

```powershell
git status --short
git diff --stat HEAD
```

Expected: `git status --short` is empty if all task commits were made. If changes remain, inspect them and commit only intentional files with a message referencing `SPEC-HR-*`.
