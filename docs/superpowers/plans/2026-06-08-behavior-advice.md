# Behavior Advice Display Card — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a behavior-based advice/encouragement display card to the Agent tab, backed by multi-day ActivityWatch data comparison with local rule-based generation and optional LLM enhancement.

**Architecture:** New `BehaviorAdviceService` computes advice from `SummaryService` multi-day behavior data using local rules; `SummaryPromptService` optionally enriches wording via LLM. Controller wires the result into the existing `/desktop/summary` response. Frontend renders a new card above the current status card.

**Tech Stack:** Java 17+ (records, streams), Javalin, vanilla ES5 JS, CSS custom properties

**Spec:** `docs/specs/behavior-advice.md` — all SPEC-ADV-* requirements

---

### File Map

| File | Action | Purpose |
|------|--------|---------|
| `self-analyst-app/src/main/java/com/selfanalyst/desktop/service/SummaryService.java` | Modify | Add `BehaviorData` record + `getBehaviorData()` method |
| `self-analyst-app/src/main/java/com/selfanalyst/desktop/service/BehaviorAdviceService.java` | Create | New service: local rules → `BehaviorAdvice` |
| `self-analyst-app/src/main/java/com/selfanalyst/desktop/service/SummaryPromptService.java` | Modify | Add `enhanceAdvice()` for LLM wording enrichment |
| `self-analyst-app/src/main/java/com/selfanalyst/desktop/controller/DesktopAgentController.java` | Modify | Wire advice into `/desktop/summary` response |
| `self-analyst-app/src/main/java/com/selfanalyst/desktop/DesktopServer.java` | Modify | Create and inject `BehaviorAdviceService` |
| `self-analyst-app/src/main/resources/desktop-ui/index.html` | Modify | Add behavior advice card HTML |
| `self-analyst-app/src/main/resources/desktop-ui/agent.js` | Modify | Add `renderBehaviorAdvice()` function |
| `self-analyst-app/src/main/resources/desktop-ui/styles.css` | Modify | Add `.behavior-advice-card` styles |
| `self-analyst-app/src/test/java/com/selfanalyst/desktop/service/BehaviorAdviceServiceTest.java` | Create | Tests for local rules |
| `scripts/check-desktop-behavior-advice.ps1` | Create | Static HTML/JS/CSS checks |

---

### Task 1: Add BehaviorData record and computation to SummaryService

**Files:**
- Modify: `self-analyst-app/src/main/java/com/selfanalyst/desktop/service/SummaryService.java`

- [ ] **Step 1: Add BehaviorData record and imports**

Add these imports at the top of `SummaryService.java`:

```java
import java.time.temporal.ChronoUnit;
import java.util.Set;
```

Add the `BehaviorData` record inside the `SummaryService` class (alongside existing `LocalFacts` and `TimelineEntry`):

```java
/**
 * Multi-day behavior comparison data for generating advice.
 * Compares recent period (last ~3 days) vs baseline (preceding ~4 days).
 */
public record BehaviorData(
        int totalDays,
        double recentDailyEntertainmentMin,
        double baselineDailyEntertainmentMin,
        double recentDailyEveningMin,
        double baselineDailyEveningMin,
        int recentDailySwitches,
        int baselineDailySwitches,
        List<String> topEntertainmentApps) {

    public boolean hasEnoughData() {
        return totalDays >= 3;
    }
}
```

- [ ] **Step 2: Add entertainment app detection**

Add the helper method to `SummaryService`:

```java
private static final Set<String> ENTERTAINMENT_KEYWORDS = Set.of(
        "youtube", "bilibili", "douyin", "tiktok", "netflix", "iqiyi",
        "youku", "tencent video", "qq音乐", "网易云音乐", "spotify",
        "steam", "epic", "游戏", "video", "twitch", "斗鱼", "huya"
);

private static boolean isEntertainmentApp(String app) {
    if (app == null) return false;
    String lower = app.toLowerCase();
    for (String kw : ENTERTAINMENT_KEYWORDS) {
        if (lower.contains(kw)) return true;
    }
    return false;
}
```

- [ ] **Step 3: Add getBehaviorData() method**

Add the method to `SummaryService`:

```java
/**
 * Computes multi-day behavior comparison data for advice generation.
 * Compares the most recent ~3 days against the preceding ~4 days.
 */
public BehaviorData getBehaviorData() {
    Instant now = Instant.now();
    ZonedDateTime localNow = now.atZone(ZONE);
    Instant sevenDaysAgo = now.minus(7, ChronoUnit.DAYS);

    List<Event> windowEvents = safeQuery(windowBucket, sevenDaysAgo, now);

    if (windowEvents.isEmpty()) {
        return new BehaviorData(0, 0, 0, 0, 0, 0, 0, List.of());
    }

    // Group events by local date
    Map<LocalDate, List<Event>> byDay = new LinkedHashMap<>();
    for (Event e : windowEvents) {
        LocalDate day = e.timestamp().atZone(ZONE).toLocalDate();
        byDay.computeIfAbsent(day, k -> new ArrayList<>()).add(e);
    }

    List<LocalDate> sortedDays = new ArrayList<>(byDay.keySet());
    sortedDays.sort(Comparator.naturalOrder());

    int totalDays = sortedDays.size();

    // Split into recent (last half, min 1) and baseline (first half)
    int splitIdx = Math.max(1, totalDays / 2);
    List<LocalDate> recentDaysList = sortedDays.subList(Math.max(0, totalDays - splitIdx), totalDays);
    List<LocalDate> baselineDaysList = sortedDays.subList(0, Math.max(0, totalDays - splitIdx));

    // Compute per-period stats
    double recentEntertainment = 0;
    double recentEvening = 0;
    int recentSwitches = 0;
    for (LocalDate day : recentDaysList) {
        for (Event e : byDay.get(day)) {
            if (e.duration() <= 0) continue;
            String app = extractApp(e.data());
            if (isEntertainmentApp(app)) {
                recentEntertainment += e.duration();
                // Check if event is after 22:00 local time
                ZonedDateTime eventTime = e.timestamp().atZone(ZONE);
                if (eventTime.getHour() >= 22) {
                    recentEvening += e.duration();
                }
            }
            recentSwitches++;
        }
    }

    double baselineEntertainment = 0;
    double baselineEvening = 0;
    int baselineSwitches = 0;
    for (LocalDate day : baselineDaysList) {
        for (Event e : byDay.get(day)) {
            if (e.duration() <= 0) continue;
            String app = extractApp(e.data());
            if (isEntertainmentApp(app)) {
                baselineEntertainment += e.duration();
                ZonedDateTime eventTime = e.timestamp().atZone(ZONE);
                if (eventTime.getHour() >= 22) {
                    baselineEvening += e.duration();
                }
            }
            baselineSwitches++;
        }
    }

    int recentDayCount = recentDaysList.size();
    int baselineDayCount = Math.max(1, baselineDaysList.size());

    // Top entertainment apps (across all days)
    Map<String, Double> entAppDurations = new LinkedHashMap<>();
    for (Event e : windowEvents) {
        if (e.duration() <= 0) continue;
        String app = extractApp(e.data());
        if (isEntertainmentApp(app)) {
            entAppDurations.merge(app, e.duration(), Double::sum);
        }
    }
    List<String> topEntApps = entAppDurations.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(5)
            .map(e -> e.getKey() + " " + formatDuration(e.getValue()))
            .toList();

    return new BehaviorData(
            totalDays,
            recentEntertainment / 60.0 / recentDayCount,
            baselineEntertainment / 60.0 / baselineDayCount,
            recentEvening / 60.0 / recentDayCount,
            baselineEvening / 60.0 / baselineDayCount,
            recentSwitches / recentDayCount,
            baselineSwitches / baselineDayCount,
            topEntApps
    );
}
```

Add the missing import for `Comparator`:
```java
import java.util.Comparator;
```

- [ ] **Step 4: Commit**

```bash
git add self-analyst-app/src/main/java/com/selfanalyst/desktop/service/SummaryService.java
git commit -m "feat: add BehaviorData record and multi-day computation to SummaryService

SPEC-ADV-SRC-001"
```

---

### Task 2: Create BehaviorAdviceService with local rules

**Files:**
- Create: `self-analyst-app/src/main/java/com/selfanalyst/desktop/service/BehaviorAdviceService.java`

- [ ] **Step 1: Create the file with BehaviorAdvice record and service**

```java
package com.selfanalyst.desktop.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Generates behavior-based advice using local rules on multi-day activity data.
 * <p>
 * Local rules implement SPEC-ADV-GEN-005:
 * <ul>
 *   <li>Entertainment time decreasing → encouragement</li>
 *   <li>Window switch count increasing significantly → suggestion</li>
 *   <li>High evening entertainment over multiple days → reminder</li>
 * </ul>
 */
public class BehaviorAdviceService {

    /**
     * Output model matching SPEC-ADV-MDL-001.
     */
    public record BehaviorAdvice(
            String type,           // encouragement | suggestion | reminder | empty
            String scopeLabel,
            String generatedAt,
            String title,
            String body,
            List<String> evidenceTags,
            Basis basis,
            String confidence,
            String emptyReason) {

        public record Basis(
                String observationRange,
                String trend,
                String adviceKind,
                String dataCompleteness) {
        }
    }

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    /**
     * Generate advice from local behavior data using deterministic rules.
     * Returns type="empty" when data is insufficient.
     */
    public BehaviorAdvice generate(SummaryService.BehaviorData data) {
        if (!data.hasEnoughData()) {
            return emptyAdvice("行为数据不足（需要至少 3 天数据），继续使用一段时间后会自动生成建议。");
        }

        Instant now = Instant.now();
        String generatedAt = ISO.format(now);
        String scopeLabel = "最近 " + data.totalDays() + " 天";

        // Rule 1: Entertainment decreasing → encouragement
        double entChange = data.baselineDailyEntertainmentMin() > 0
                ? (data.recentDailyEntertainmentMin() - data.baselineDailyEntertainmentMin())
                  / data.baselineDailyEntertainmentMin()
                : 0;
        if (entChange < -0.15 && data.baselineDailyEntertainmentMin() > 10) {
            return encouragement(data, generatedAt, scopeLabel);
        }

        // Rule 2: Window switches increasing significantly → suggestion
        double switchChange = data.baselineDailySwitches() > 0
                ? (double) (data.recentDailySwitches() - data.baselineDailySwitches())
                  / data.baselineDailySwitches()
                : 0;
        if (switchChange > 0.25 && data.baselineDailySwitches() > 50) {
            return suggestion(data, generatedAt, scopeLabel);
        }

        // Rule 3: High evening entertainment → reminder
        if (data.recentDailyEveningMin() > 90) {
            return reminder(data, generatedAt, scopeLabel);
        }

        // Default: if entertainment is stable or slightly decreasing, give encouragement
        if (entChange <= 0.05 && data.recentDailyEntertainmentMin() < 120) {
            return encouragement(data, generatedAt, scopeLabel);
        }

        // Fallback: mild suggestion
        return mildSuggestion(data, generatedAt, scopeLabel);
    }

    private BehaviorAdvice encouragement(SummaryService.BehaviorData data, String generatedAt, String scopeLabel) {
        return new BehaviorAdvice(
                "encouragement",
                scopeLabel,
                generatedAt,
                "近几天娱乐类应用时长有所下降，继续保持当前节奏。",
                "最近 " + data.totalDays() + " 天内，娱乐类应用日均时长从 "
                + String.format("%.0f", data.baselineDailyEntertainmentMin()) + " 分钟降至 "
                + String.format("%.0f", data.recentDailyEntertainmentMin()) + " 分钟，呈改善趋势。",
                buildEvidenceTags(data),
                new BehaviorAdvice.Basis(scopeLabel, "改善", "保持策略", completeness(data)),
                confidence(data),
                null
        );
    }

    private BehaviorAdvice suggestion(SummaryService.BehaviorData data, String generatedAt, String scopeLabel) {
        int diff = data.recentDailySwitches() - data.baselineDailySwitches();
        return new BehaviorAdvice(
                "suggestion",
                scopeLabel,
                generatedAt,
                "窗口切换次数明显增加，建议尝试减少多任务切换以提升专注度。",
                "最近平均每天切换窗口 " + data.recentDailySwitches() + " 次，比前期增加约 "
                + diff + " 次，频繁的上下文切换可能降低效率。",
                buildEvidenceTags(data),
                new BehaviorAdvice.Basis(scopeLabel, "上升", "调整策略", completeness(data)),
                confidence(data),
                null
        );
    }

    private BehaviorAdvice reminder(SummaryService.BehaviorData data, String generatedAt, String scopeLabel) {
        return new BehaviorAdvice(
                "reminder",
                scopeLabel,
                generatedAt,
                "最近晚间娱乐类应用使用时间偏高，建议在 22:00 后逐步收尾。",
                "最近平均每天 22:00 后娱乐应用时长约 "
                + String.format("%.0f", data.recentDailyEveningMin()) + " 分钟，可能影响次日状态和作息规律。",
                buildEvidenceTags(data),
                new BehaviorAdvice.Basis(scopeLabel, "偏高", "调整策略", completeness(data)),
                confidence(data),
                null
        );
    }

    private BehaviorAdvice mildSuggestion(SummaryService.BehaviorData data, String generatedAt, String scopeLabel) {
        return new BehaviorAdvice(
                "suggestion",
                scopeLabel,
                generatedAt,
                "活动数据稳定，可考虑设定每日专注时段以进一步提升效率。",
                "最近 " + data.totalDays() + " 天娱乐类应用日均 "
                + String.format("%.0f", data.recentDailyEntertainmentMin()) + " 分钟，整体模式稳定。",
                buildEvidenceTags(data),
                new BehaviorAdvice.Basis(scopeLabel, "稳定", "优化策略", completeness(data)),
                confidence(data),
                null
        );
    }

    private BehaviorAdvice emptyAdvice(String reason) {
        Instant now = Instant.now();
        return new BehaviorAdvice(
                "empty",
                "数据不足",
                ISO.format(now),
                "还没有足够行为数据生成建议。继续使用一段时间后，这里会出现基于过往行为的提醒或鼓励。",
                "",
                List.of(),
                new BehaviorAdvice.Basis("数据不足", "—", "—", "低"),
                "low",
                reason
        );
    }

    private List<String> buildEvidenceTags(SummaryService.BehaviorData data) {
        List<String> tags = new java.util.ArrayList<>();
        double entChange = data.baselineDailyEntertainmentMin() > 0
                ? (data.recentDailyEntertainmentMin() - data.baselineDailyEntertainmentMin())
                  / data.baselineDailyEntertainmentMin()
                : 0;
        if (entChange < -0.10) {
            tags.add("娱乐时长下降");
        } else if (entChange > 0.10) {
            tags.add("娱乐时长上升");
        }
        if (data.recentDailyEveningMin() > 60) {
            tags.add("晚间娱乐偏高");
        }
        int switchDiff = data.recentDailySwitches() - data.baselineDailySwitches();
        if (Math.abs(switchDiff) > 20) {
            tags.add(switchDiff > 0 ? "窗口切换增加" : "窗口切换减少");
        }
        if (!data.topEntertainmentApps().isEmpty()) {
            tags.add("常用娱乐应用");
        }
        if (tags.isEmpty()) {
            tags.add("活动模式稳定");
        }
        // Limit to 5 per SPEC-ADV-MDL-003
        return tags.size() > 5 ? tags.subList(0, 5) : tags;
    }

    private String confidence(SummaryService.BehaviorData data) {
        return data.totalDays() >= 7 ? "medium"
                : data.totalDays() >= 5 ? "medium"
                : "low";
    }

    private String completeness(SummaryService.BehaviorData data) {
        return data.totalDays() >= 7 ? "中"
                : data.totalDays() >= 5 ? "中"
                : "低";
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add self-analyst-app/src/main/java/com/selfanalyst/desktop/service/BehaviorAdviceService.java
git commit -m "feat: add BehaviorAdviceService with local rule-based advice generation

SPEC-ADV-GEN-003, SPEC-ADV-GEN-005"
```

---

### Task 3: Add LLM advice enhancement to SummaryPromptService

**Files:**
- Modify: `self-analyst-app/src/main/java/com/selfanalyst/desktop/service/SummaryPromptService.java`

- [ ] **Step 1: Add enhanceAdvice() method**

Add this method to `SummaryPromptService`:

```java
/**
 * Enrich behavior advice wording with LLM while preserving local facts.
 * Falls back to the original advice when LLM is unavailable.
 *
 * @param advice local rule-generated advice (never null, never type="empty")
 * @param client nullable – text completion client
 * @return enriched advice (or original if LLM unavailable)
 */
public BehaviorAdviceService.BehaviorAdvice enhanceAdvice(
        BehaviorAdviceService.BehaviorAdvice advice,
        SummaryTextClient client) {
    if (client == null || advice == null || "empty".equals(advice.type())) {
        return advice;
    }

    try {
        String prompt = buildAdvicePrompt(advice);
        String response = client.complete(prompt, Duration.ofSeconds(5));
        if (response == null || response.isBlank()) {
            return advice;
        }
        return parseAdviceResponse(advice, response);
    } catch (Exception e) {
        return advice;
    }
}

private static String buildAdvicePrompt(BehaviorAdviceService.BehaviorAdvice advice) {
    return """
            你是 SelfAnalyst，请基于以下行为分析结果优化建议措辞，使其更自然、共情。
            输出格式为 JSON，不要输出其他内容：
            {
              "title": "优化后的主结论（1句话，不超过80字）",
              "body": "优化后的解释正文（不超过240字）",
              "confidence": "high 或 medium 或 low"
            }

            当前建议：
            - 类型: %s
            - 主结论: %s
            - 解释: %s
            - 证据: %s
            - 趋势: %s
            """.formatted(
            advice.type(),
            advice.title(),
            advice.body(),
            advice.evidenceTags() != null ? String.join(", ", advice.evidenceTags()) : "无",
            advice.basis() != null ? advice.basis().trend() : "未知"
    );
}

private static BehaviorAdviceService.BehaviorAdvice parseAdviceResponse(
        BehaviorAdviceService.BehaviorAdvice original, String response) {
    try {
        String json = response;
        int braceStart = json.indexOf('{');
        int braceEnd = json.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            json = json.substring(braceStart, braceEnd + 1);
        }
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = mapper.readValue(json, Map.class);

        String title = stringOr(map.get("title"), original.title());
        String body = stringOr(map.get("body"), original.body());
        String confidence = stringOr(map.get("confidence"), original.confidence());

        // Preserve local facts — only replace wording per SPEC-ADV-GEN-006
        return new BehaviorAdviceService.BehaviorAdvice(
                original.type(),
                original.scopeLabel(),
                original.generatedAt(),
                title,
                body,
                original.evidenceTags(),
                original.basis(),
                confidence,
                original.emptyReason()
        );
    } catch (Exception e) {
        return original;
    }
}
```

Add the import for `Map` at the top:
```java
import java.util.Map;
```

(Note: `Map` import may already be present from the existing `parseEnhanced` method.)

- [ ] **Step 2: Commit**

```bash
git add self-analyst-app/src/main/java/com/selfanalyst/desktop/service/SummaryPromptService.java
git commit -m "feat: add LLM advice wording enhancement to SummaryPromptService

SPEC-ADV-GEN-002, SPEC-ADV-GEN-006"
```

---

### Task 4: Wire BehaviorAdvice into DesktopAgentController and DesktopServer

**Files:**
- Modify: `self-analyst-app/src/main/java/com/selfanalyst/desktop/controller/DesktopAgentController.java`
- Modify: `self-analyst-app/src/main/java/com/selfanalyst/desktop/DesktopServer.java`

- [ ] **Step 1: Add BehaviorAdviceService field and update constructor in DesktopAgentController**

In `DesktopAgentController.java`, add the field:

```java
private final BehaviorAdviceService adviceService;
```

Update the constructor to accept `BehaviorAdviceService`:

```java
public DesktopAgentController(SummaryService summaryService,
                              BehaviorAdviceService adviceService,
                              SelfAnalystAgent agent,
                              TaskStore taskStore,
                              Config config) {
    this.summaryService = summaryService;
    this.adviceService = adviceService;
    this.promptService = new SummaryPromptService();
    this.agent = agent;
    this.taskStore = taskStore;
    this.config = config;
}
```

Add the import:
```java
import com.selfanalyst.desktop.service.BehaviorAdviceService;
```

- [ ] **Step 2: Add behaviorAdvice to getSummary response**

In `getSummary()`, add after the `result.put("current", currentMap);` line but before `result.put("timeline", timelineList);`:

```java
// Generate behavior advice (SPEC-ADV-API-001)
Map<String, Object> adviceMap;
try {
    SummaryService.BehaviorData behaviorData = summaryService.getBehaviorData();
    BehaviorAdviceService.BehaviorAdvice rawAdvice = adviceService.generate(behaviorData);

    BehaviorAdviceService.BehaviorAdvice finalAdvice;
    if (!"empty".equals(rawAdvice.type()) && llmAvailable) {
        finalAdvice = promptService.enhanceAdvice(rawAdvice, summaryClient);
    } else {
        finalAdvice = rawAdvice;
    }

    adviceMap = new LinkedHashMap<>();
    adviceMap.put("type", finalAdvice.type());
    adviceMap.put("scopeLabel", finalAdvice.scopeLabel());
    adviceMap.put("generatedAt", finalAdvice.generatedAt());
    adviceMap.put("title", finalAdvice.title());
    adviceMap.put("body", finalAdvice.body());
    adviceMap.put("evidenceTags", finalAdvice.evidenceTags());
    adviceMap.put("confidence", finalAdvice.confidence());
    adviceMap.put("emptyReason", finalAdvice.emptyReason());

    Map<String, Object> basisMap = new LinkedHashMap<>();
    if (finalAdvice.basis() != null) {
        basisMap.put("observationRange", finalAdvice.basis().observationRange());
        basisMap.put("trend", finalAdvice.basis().trend());
        basisMap.put("adviceKind", finalAdvice.basis().adviceKind());
        basisMap.put("dataCompleteness", finalAdvice.basis().dataCompleteness());
    }
    adviceMap.put("basis", basisMap);
} catch (Exception e) {
    // SPEC-ADV-API-003: advice failure must not fail the whole summary
    adviceMap = new LinkedHashMap<>();
    adviceMap.put("type", "empty");
    adviceMap.put("scopeLabel", "数据不足");
    adviceMap.put("generatedAt", java.time.Instant.now().toString());
    adviceMap.put("title", "暂时无法生成行为建议");
    adviceMap.put("body", "");
    adviceMap.put("evidenceTags", List.of());
    adviceMap.put("confidence", "low");
    adviceMap.put("emptyReason", "生成失败: " + e.getMessage());
    adviceMap.put("basis", Map.of("observationRange", "—", "trend", "—", "adviceKind", "—", "dataCompleteness", "低"));
}
result.put("behaviorAdvice", adviceMap);
```

- [ ] **Step 3: Update DesktopServer to create and inject BehaviorAdviceService**

In `DesktopServer.java`:

```java
import com.selfanalyst.desktop.service.BehaviorAdviceService;
```

In the constructor, after creating `summaryService`:

```java
SummaryService summaryService = new SummaryService(eventStore, memoryStore);
BehaviorAdviceService adviceService = new BehaviorAdviceService();
```

Update the controller creation:

```java
this.agentCtrl = new DesktopAgentController(summaryService, adviceService, agent, taskStore, config);
```

- [ ] **Step 4: Commit**

```bash
git add self-analyst-app/src/main/java/com/selfanalyst/desktop/controller/DesktopAgentController.java
git add self-analyst-app/src/main/java/com/selfanalyst/desktop/DesktopServer.java
git commit -m "feat: wire BehaviorAdvice into /desktop/summary API response

SPEC-ADV-API-001, SPEC-ADV-API-002, SPEC-ADV-API-003"
```

---

### Task 5: Add behavior advice card HTML to Agent tab

**Files:**
- Modify: `self-analyst-app/src/main/resources/desktop-ui/index.html`

- [ ] **Step 1: Add behavior advice card section**

In `index.html`, insert after `<div class="agent-left">` and before `<!-- Current Status Card -->`:

```html
<!-- Behavior Advice Card (SPEC-ADV-IA-001, SPEC-ADV-IA-002) -->
<div id="behavior-advice-card" class="card behavior-advice-card hidden">
  <div class="card-body" id="behavior-advice-body">
    <div class="loading-placeholder">分析行为数据中...</div>
  </div>
</div>
```

- [ ] **Step 2: Commit**

```bash
git add self-analyst-app/src/main/resources/desktop-ui/index.html
git commit -m "feat: add behavior advice card container to Agent tab HTML

SPEC-ADV-IA-001, SPEC-ADV-IA-002"
```

---

### Task 6: Add behavior advice card CSS styles

**Files:**
- Modify: `self-analyst-app/src/main/resources/desktop-ui/styles.css`

- [ ] **Step 1: Add behavior advice card styles**

Add the following CSS after the `#current-status-card` styles block (after line 406, after the `.status-suggestion` rule):

```css
/* ----- Behavior Advice Card (SPEC-ADV-UI-*) ----- */
.behavior-advice-card {
  flex-shrink: 0;
  margin: 12px 12px 0 12px;
  border-left: 4px solid var(--accent);
}

.behavior-advice-card.type-encouragement {
  border-left-color: #26a69a;
}
.behavior-advice-card.type-suggestion {
  border-left-color: var(--accent);
}
.behavior-advice-card.type-reminder {
  border-left-color: var(--orange);
}
.behavior-advice-card.type-empty {
  border-left-color: var(--gray);
}

.behavior-advice-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.behavior-advice-type-tag {
  display: inline-block;
  padding: 2px 10px;
  font-size: 11px;
  font-weight: 600;
  border-radius: 10px;
  line-height: 1.5;
  text-transform: uppercase;
}
.behavior-advice-type-tag.tag-encouragement {
  background: rgba(38, 166, 154, 0.15);
  color: #26a69a;
}
.behavior-advice-type-tag.tag-suggestion {
  background: var(--accent-dim);
  color: var(--accent);
}
.behavior-advice-type-tag.tag-reminder {
  background: var(--orange-dim);
  color: var(--orange);
}
.behavior-advice-type-tag.tag-empty {
  background: var(--gray-dim);
  color: var(--gray);
}

.behavior-advice-scope {
  font-size: 11px;
  color: var(--text-muted);
}

.behavior-advice-updated {
  font-size: 11px;
  color: var(--text-muted);
  margin-left: auto;
}

.behavior-advice-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 8px;
  line-height: 1.5;
}

.behavior-advice-body {
  font-size: 12px;
  color: var(--text-secondary);
  margin-bottom: 10px;
  line-height: 1.5;
}

.behavior-advice-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: 8px;
}

.behavior-advice-tag {
  display: inline-block;
  padding: 1px 8px;
  font-size: 11px;
  background: var(--bg-input);
  color: var(--text-secondary);
  border-radius: 10px;
  line-height: 1.5;
}

.behavior-advice-basis {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  font-size: 11px;
  color: var(--text-muted);
  padding-top: 8px;
  border-top: 1px solid var(--border-color);
}

.behavior-advice-basis-item {
  display: inline-flex;
  align-items: baseline;
  gap: 3px;
}

.behavior-advice-basis-item strong {
  font-weight: 500;
  color: var(--text-secondary);
}

.behavior-advice-empty {
  color: var(--text-muted);
  font-style: italic;
  text-align: center;
  padding: 16px;
  font-size: 13px;
}

/* Responsive: stack basis below body on narrow screens (SPEC-ADV-UI-005) */
@media (max-width: 800px) {
  .behavior-advice-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 4px;
  }
  .behavior-advice-updated {
    margin-left: 0;
  }
  .behavior-advice-basis {
    flex-direction: column;
    gap: 2px;
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add self-analyst-app/src/main/resources/desktop-ui/styles.css
git commit -m "feat: add behavior advice card styles with type-specific theming

SPEC-ADV-UI-004, SPEC-ADV-UI-005"
```

---

### Task 7: Add renderBehaviorAdvice() to agent.js

**Files:**
- Modify: `self-analyst-app/src/main/resources/desktop-ui/agent.js`

- [ ] **Step 1: Add DOM reference helper and update renderAgentTab**

Add a helper to get the advice card element (since it's not in `state.dom` initially) and update `renderAgentTab`:

```javascript
function renderAgentTab() {
  renderBehaviorAdvice();
  renderStatusCard();
  renderTimeline();
  renderTasks();
}
```

- [ ] **Step 2: Add renderBehaviorAdvice() function**

Add the function before `renderStatusCard`:

```javascript
// ---- Behavior Advice Card (SPEC-ADV-UI-*) ----

function renderBehaviorAdvice() {
  var card = document.getElementById("behavior-advice-card");
  var body = document.getElementById("behavior-advice-body");
  if (!card || !body) return;

  var sm = state.summary;
  var advice = sm && sm.behaviorAdvice ? sm.behaviorAdvice : null;

  // SPEC-ADV-API-004: handle null behaviorAdvice from old responses
  if (!advice) {
    card.classList.add("hidden");
    return;
  }

  card.classList.remove("hidden");

  // Remove old type classes
  card.classList.remove("type-encouragement", "type-suggestion", "type-reminder", "type-empty");

  if (advice.type === "empty") {
    // SPEC-ADV-UI-002: Empty state
    card.classList.add("type-empty");
    body.innerHTML =
      '<div class="behavior-advice-empty">' +
      escHtml(advice.title || "还没有足够行为数据生成建议。继续使用一段时间后，这里会出现基于过往行为的提醒或鼓励。") +
      "</div>";
    return;
  }

  // SPEC-ADV-UI-003: Full card content
  card.classList.add("type-" + (advice.type || "suggestion"));

  var typeLabels = { encouragement: "鼓励", suggestion: "建议", reminder: "提醒" };
  var typeLabel = typeLabels[advice.type] || "建议";

  var updatedText = advice.generatedAt
    ? formatRelativeTime(advice.generatedAt)
    : "";

  var html = "";

  // Header: type tag + scope + updated time
  html += '<div class="behavior-advice-header">';
  html +=
    '<span class="behavior-advice-type-tag tag-' +
    escHtml(advice.type || "suggestion") +
    '">' +
    escHtml(typeLabel) +
    "</span>";
  if (advice.scopeLabel) {
    html +=
      '<span class="behavior-advice-scope">基于' +
      escHtml(advice.scopeLabel) +
      "</span>";
  }
  if (updatedText) {
    html +=
      '<span class="behavior-advice-updated">' +
      escHtml(updatedText) +
      "更新</span>";
  }
  html += "</div>";

  // Title
  if (advice.title) {
    html +=
      '<div class="behavior-advice-title">' + escHtml(advice.title) + "</div>";
  }

  // Body
  if (advice.body) {
    html +=
      '<div class="behavior-advice-body">' + escHtml(advice.body) + "</div>";
  }

  // Evidence tags (SPEC-ADV-UI-003)
  if (advice.evidenceTags && advice.evidenceTags.length) {
    html += '<div class="behavior-advice-tags">';
    advice.evidenceTags.forEach(function (tag) {
      html +=
        '<span class="behavior-advice-tag">' + escHtml(tag) + "</span>";
    });
    html += "</div>";
  }

  // Basis summary (SPEC-ADV-UI-003)
  if (advice.basis) {
    html += '<div class="behavior-advice-basis">';
    if (advice.basis.observationRange) {
      html +=
        '<span class="behavior-advice-basis-item"><strong>观察范围</strong> ' +
        escHtml(advice.basis.observationRange) +
        "</span>";
    }
    if (advice.basis.trend) {
      html +=
        '<span class="behavior-advice-basis-item"><strong>趋势</strong> ' +
        escHtml(advice.basis.trend) +
        "</span>";
    }
    if (advice.basis.adviceKind) {
      html +=
        '<span class="behavior-advice-basis-item"><strong>建议类型</strong> ' +
        escHtml(advice.basis.adviceKind) +
        "</span>";
    }
    if (advice.basis.dataCompleteness) {
      html +=
        '<span class="behavior-advice-basis-item"><strong>数据完整度</strong> ' +
        escHtml(advice.basis.dataCompleteness) +
        "</span>";
    }
    html += "</div>";
  }

  body.innerHTML = html;
}
```

- [ ] **Step 3: Commit**

```bash
git add self-analyst-app/src/main/resources/desktop-ui/agent.js
git commit -m "feat: add renderBehaviorAdvice() with type-tagged card rendering

SPEC-ADV-UI-001, SPEC-ADV-UI-002, SPEC-ADV-UI-003, SPEC-ADV-UI-006"
```

---

### Task 8: Add backend tests

**Files:**
- Create: `self-analyst-app/src/test/java/com/selfanalyst/desktop/service/BehaviorAdviceServiceTest.java`

- [ ] **Step 1: Create BehaviorAdviceServiceTest**

```java
package com.selfanalyst.desktop.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BehaviorAdviceServiceTest {

    private final BehaviorAdviceService service = new BehaviorAdviceService();

    @Test
    void returnsEmptyWhenInsufficientData() {
        SummaryService.BehaviorData data = new SummaryService.BehaviorData(
                2, 30, 40, 15, 20, 100, 80, List.of());

        BehaviorAdviceService.BehaviorAdvice advice = service.generate(data);

        assertEquals("empty", advice.type());
        assertNotNull(advice.emptyReason());
        assertTrue(advice.emptyReason().contains("不足"));
    }

    @Test
    void returnsEncouragementWhenEntertainmentDecreasing() {
        SummaryService.BehaviorData data = new SummaryService.BehaviorData(
                7, 40, 60, 20, 30, 100, 90, List.of("bilibili 2小时"));

        BehaviorAdviceService.BehaviorAdvice advice = service.generate(data);

        assertEquals("encouragement", advice.type());
        assertNotNull(advice.title());
        assertFalse(advice.title().isBlank());
        assertNotNull(advice.body());
        assertNotNull(advice.evidenceTags());
        assertFalse(advice.evidenceTags().isEmpty());
    }

    @Test
    void returnsSuggestionWhenSwitchesIncreasing() {
        SummaryService.BehaviorData data = new SummaryService.BehaviorData(
                7, 30, 30, 10, 10, 150, 100, List.of());

        BehaviorAdviceService.BehaviorAdvice advice = service.generate(data);

        assertEquals("suggestion", advice.type());
        assertNotNull(advice.title());
        assertNotNull(advice.body());
        assertNotNull(advice.basis());
    }

    @Test
    void returnsReminderWhenEveningEntertainmentHigh() {
        SummaryService.BehaviorData data = new SummaryService.BehaviorData(
                7, 50, 30, 100, 40, 80, 70, List.of("netflix 3小时"));

        BehaviorAdviceService.BehaviorAdvice advice = service.generate(data);

        assertEquals("reminder", advice.type());
        assertTrue(advice.title().contains("晚间") || advice.body().contains("22:00"));
    }

    @Test
    void returnsValidBasisOnAllNonEmptyAdvice() {
        SummaryService.BehaviorData data = new SummaryService.BehaviorData(
                7, 40, 60, 20, 30, 100, 90, List.of("bilibili 2小时"));

        BehaviorAdviceService.BehaviorAdvice advice = service.generate(data);

        assertNotNull(advice.basis());
        assertNotNull(advice.basis().observationRange());
        assertNotNull(advice.basis().trend());
        assertNotNull(advice.basis().adviceKind());
        assertNotNull(advice.basis().dataCompleteness());
    }

    @Test
    void evidenceTagsNeverExceedFive() {
        SummaryService.BehaviorData data = new SummaryService.BehaviorData(
                7, 10, 50, 5, 30, 100, 80,
                List.of("bilibili 2小时", "douyin 1小时", "netflix 1小时"));

        BehaviorAdviceService.BehaviorAdvice advice = service.generate(data);

        assertNotNull(advice.evidenceTags());
        assertTrue(advice.evidenceTags().size() <= 5);
    }

    @Test
    void emptyAdviceHasNullEmptyReasonForTypeEmpty() {
        SummaryService.BehaviorData data = new SummaryService.BehaviorData(
                0, 0, 0, 0, 0, 0, 0, List.of());

        BehaviorAdviceService.BehaviorAdvice advice = service.generate(data);

        assertEquals("empty", advice.type());
        assertNotNull(advice.emptyReason());
    }

    @Test
    void returnsAdviceEvenWithoutEntertainmentApps() {
        SummaryService.BehaviorData data = new SummaryService.BehaviorData(
                7, 0, 0, 0, 0, 60, 55, List.of());

        BehaviorAdviceService.BehaviorAdvice advice = service.generate(data);

        assertNotNull(advice.type());
        // Should still produce some advice, not crash
        assertFalse(advice.title().isBlank());
    }
}
```

- [ ] **Step 2: Run tests to verify they pass**

```powershell
mvn test -pl self-analyst-app -Dtest=BehaviorAdviceServiceTest
```

Expected: All tests PASS.

- [ ] **Step 3: Commit**

```bash
git add self-analyst-app/src/test/java/com/selfanalyst/desktop/service/BehaviorAdviceServiceTest.java
git commit -m "test: add BehaviorAdviceService unit tests for local rules

SPEC-ADV-TST-001, SPEC-ADV-TST-002, SPEC-ADV-TST-003"
```

---

### Task 9: Create static check script

**Files:**
- Create: `scripts/check-desktop-behavior-advice.ps1`

- [ ] **Step 1: Create the check script**

```powershell
$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$AppUi = Join-Path $Root "self-analyst-app\src\main\resources\desktop-ui"

function Assert-Contains {
    param(
        [string]$Path,
        [string]$Pattern,
        [string]$Message
    )

    $text = Get-Content -LiteralPath $Path -Raw
    if ($text -notmatch $Pattern) {
        throw "$Message ($Path)"
    }
}

$index = Join-Path $AppUi "index.html"
$agentJs = Join-Path $AppUi "agent.js"
$styles = Join-Path $AppUi "styles.css"

# HTML checks
Assert-Contains $index 'id="behavior-advice-card"' "Missing behavior advice card container"

# JS checks
Assert-Contains $agentJs 'function\s+renderBehaviorAdvice\s*\(' "Missing renderBehaviorAdvice function"
Assert-Contains $agentJs 'escHtml' "renderBehaviorAdvice must use escHtml for text safety (SPEC-ADV-ERR-004)"

# CSS checks
Assert-Contains $styles '\.behavior-advice-card' "Missing behavior advice card styles"
Assert-Contains $styles 'type-encouragement' "Missing encouragement type styling (SPEC-ADV-UI-004)"
Assert-Contains $styles 'type-suggestion' "Missing suggestion type styling (SPEC-ADV-UI-004)"
Assert-Contains $styles 'type-reminder' "Missing reminder type styling (SPEC-ADV-UI-004)"
Assert-Contains $styles 'type-empty' "Missing empty type styling (SPEC-ADV-UI-004)"

Write-Output "desktop behavior advice static checks passed"
```

- [ ] **Step 2: Run the check script**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/check-desktop-behavior-advice.ps1
```

Expected: "desktop behavior advice static checks passed"

- [ ] **Step 3: Commit**

```bash
git add scripts/check-desktop-behavior-advice.ps1
git commit -m "feat: add static check script for behavior advice card"
```

---

### Task 10: Full build and verification

- [ ] **Step 1: Run full Maven test suite**

```powershell
mvn test
```

Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 2: Run all static check scripts**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/check-desktop-chat-tab.ps1
powershell -ExecutionPolicy Bypass -File scripts/check-desktop-behavior-advice.ps1
```

Expected: Both scripts output "passed".

- [ ] **Step 3: Commit any fixes if needed**

Only if the above steps revealed issues that required fixes.

---

### Traceability Matrix

| Spec ID | Task(s) |
|---------|---------|
| SPEC-ADV-GOAL-001 through 005 | All tasks |
| SPEC-ADV-NON-001 through 006 | Task 7 (no action buttons rendered) |
| SPEC-ADV-IA-001 through 004 | Task 5 (HTML placement) |
| SPEC-ADV-MDL-001 through 003 | Tasks 1, 2 (data model) |
| SPEC-ADV-SRC-001 through 004 | Tasks 1, 3 (data sources) |
| SPEC-ADV-GEN-001 through 006 | Tasks 2, 3 (generation rules) |
| SPEC-ADV-API-001 through 004 | Task 4 (API contract) |
| SPEC-ADV-UI-001 through 006 | Tasks 6, 7 (frontend) |
| SPEC-ADV-ERR-001 through 005 | Tasks 4, 7 (error handling) |
| SPEC-ADV-PRV-001 through 004 | Tasks 3, 7 (privacy) |
| SPEC-ADV-TST-001 through 005 | Tasks 8, 9 (testing) |
| SPEC-ADV-ACC-001 through 008 | Task 10 (acceptance) |
