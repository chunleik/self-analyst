package com.selfanalyst.config;

import com.selfanalyst.wiki.WikiPrivacyPolicy;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** 采集与模型可见规则共享的不可变快照，每次保存原子替换。 */
public final class PrivacyRuntime {
    private record Snapshot(Set<String> captureApps, WikiPrivacyPolicy modelPolicy) {}
    private final AtomicReference<Snapshot> current = new AtomicReference<>();

    public PrivacyRuntime(Config config) {
        this(config.collectionExcludeApps(), config.wikiExcludeApps(), config.wikiExcludeSites());
    }

    public PrivacyRuntime(String collectionApps, String wikiApps, String sites) {
        apply(collectionApps, wikiApps, sites);
    }

    public Predicate<String> captureExcluded() {
        return app -> app != null && current.get().captureApps().contains(app.toLowerCase(Locale.ROOT));
    }

    /** 调用方在每次处理开始时获取一次策略，整个处理期间保留该快照。 */
    public Supplier<WikiPrivacyPolicy> modelPolicy() {
        return () -> current.get().modelPolicy();
    }

    public void apply(String collectionApps, String wikiApps, String sites) {
        Set<String> captureApps = collectionApps == null ? Set.of()
                : Arrays.stream(collectionApps.split("[,;]")).map(String::strip)
                        .filter(app -> !app.isEmpty()).map(app -> app.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
        WikiPrivacyPolicy model = WikiPrivacyPolicy.of(String.join(",", captureApps)
                + "," + (wikiApps == null ? "" : wikiApps), sites);
        current.set(new Snapshot(captureApps, model));
    }
}
