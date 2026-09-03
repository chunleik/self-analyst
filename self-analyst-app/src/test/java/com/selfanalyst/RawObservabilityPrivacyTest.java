package com.selfanalyst;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;

class RawObservabilityPrivacyTest {
    private static final Pattern SENSITIVE_LOG_ARGUMENT = Pattern.compile(
            "(?s)log\\.(?:trace|debug|info|warn|error)\\([^;]*(?:canonicalDataJson|"
                    + "event\\.data\\(|request\\.body|prompt|file,|dir,|abs\\))");

    @Test
    void collectionAndProjectionSourcesDoNotLogPayloadPathsTokensOrPrompts()
            throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("self-analyst-aw"))) {
            root = root.getParent();
        }
        if (root == null) throw new IllegalStateException("无法定位多模块仓库根目录");
        for (Path sourceRoot : List.of(
                root.resolve("self-analyst-aw/src/main/java/com/selfanalyst/aw/raw"),
                root.resolve("self-analyst-aw/src/main/java/com/selfanalyst/aw/projection"),
                root.resolve("self-analyst-aw/src/main/java/com/selfanalyst/aw/watcher"),
                root.resolve("self-analyst-content/src/main/java"),
                root.resolve("self-analyst-file/src/main/java"))) {
            try (var files = Files.walk(sourceRoot)) {
                for (Path source : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    assertFalse(SENSITIVE_LOG_ARGUMENT.matcher(Files.readString(source)).find(),
                            source.toString());
                }
            }
        }
    }
}
