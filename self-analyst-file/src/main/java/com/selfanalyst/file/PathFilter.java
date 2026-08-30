package com.selfanalyst.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Single source of exclusion rules (SPEC-FILE-020..024). Called consistently at
 * FileWatcher registration, FileIndexWorker scan, and before enqueue.
 */
public class PathFilter {

    private static final Logger log = LoggerFactory.getLogger(PathFilter.class);

    /** SPEC-FILE-020: built-in blacklisted directory names. */
    private static final Set<String> BLACKLIST_DIRS = Set.of(
            ".git", "node_modules", "target", "build", "dist", ".gradle",
            ".idea", ".vscode", "out", "bin", ".mvn", "__pycache__", "venv", ".venv");

    /** SPEC-FILE-022: default-excluded sensitive file globs. */
    private static final List<String> SENSITIVE_GLOBS = List.of(
            ".env", ".env.*", "*.pem", "*.key", "id_rsa*", "*.p12", "*.keystore");

    /** High-churn / volatile files that must never be collected. */
    private static final List<String> VOLATILE_GLOBS = List.of(
            "*.log", "*.tmp", "*.temp", "*.lock", "*.swp", "*~");

    private final long maxFileSizeBytes;
    private final Set<String> excludeDirs;
    private final List<PathMatcher> excludeGlobs;
    private final List<PathMatcher> sensitiveMatchers;
    private final List<PathMatcher> volatileMatchers;
    private final Set<String> allowedExtensions;   // empty = all non-excluded file types

    public PathFilter(long maxFileSizeKb, List<String> excludeDirs,
                      List<String> excludeGlobs, List<String> extensions) {
        this.maxFileSizeBytes = Math.max(0, maxFileSizeKb) * 1024L;
        this.excludeDirs = new LinkedHashSet<>();
        if (excludeDirs != null) {
            for (String d : excludeDirs) {
                if (d != null && !d.isBlank()) this.excludeDirs.add(d.trim());
            }
        }
        this.excludeGlobs = compileGlobs(excludeGlobs);
        this.sensitiveMatchers = compileGlobs(SENSITIVE_GLOBS);
        this.volatileMatchers = compileGlobs(VOLATILE_GLOBS);
        this.allowedExtensions = new LinkedHashSet<>();
        if (extensions != null) {
            for (String e : extensions) {
                if (e != null && !e.isBlank()) {
                    this.allowedExtensions.add(normalizeExt(e.trim()));
                }
            }
        }
    }

    /** True if the directory should NOT be descended into / registered. */
    public boolean isExcludedDir(Path dir) {
        if (dir == null) return false;
        String name = fileName(dir);
        if (name.isEmpty()) return false;
        if (isNameBlacklistedDir(name)) return true;
        if (isHidden(dir, name)) return true;
        return false;
    }

    /** Name-only blacklist check, excluding the hidden test. */
    private boolean isNameBlacklistedDir(String name) {
        return BLACKLIST_DIRS.contains(name) || excludeDirs.contains(name);
    }

    /**
     * True if the file should NOT be collected. Checks (in order): containment in
     * an excluded dir, hidden, sensitive, volatile, extension allow-list, custom
     * globs, and size cap (last, since it touches the filesystem).
     */
    public boolean isExcludedFile(Path file) {
        if (file == null) return true;
        String name = fileName(file);
        if (name.isEmpty()) return true;

        // Any ancestor directory blacklisted by name → file excluded. (Name-only:
        // a hidden ancestor outside the watch tree — e.g. Windows AppData — must
        // not disqualify the file; hidden applies to the file and to traversal.)
        for (Path p = file.getParent(); p != null; p = p.getParent()) {
            if (isNameBlacklistedDir(fileName(p))) return true;
        }

        if (isHidden(file, name)) return true;
        if (matchesAny(sensitiveMatchers, file, name)) return true;
        if (matchesAny(volatileMatchers, file, name)) return true;

        if (!allowedExtensions.isEmpty()
                && !allowedExtensions.contains(extensionOf(name))) {
            return true;
        }
        if (matchesAny(excludeGlobs, file, name)) return true;

        // SPEC-FILE-021: oversized files are filtered by filesystem metadata.
        if (maxFileSizeBytes > 0) {
            try {
                if (Files.isRegularFile(file) && Files.size(file) > maxFileSizeBytes) {
                    return true;
                }
            } catch (IOException e) {
                return true; // can't stat → treat as excluded
            }
        }
        return false;
    }

    /** Lower-cased extension without the dot, or "" if none. */
    public static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalizeExt(String ext) {
        String e = ext.toLowerCase(Locale.ROOT);
        return e.startsWith(".") ? e.substring(1) : e;
    }

    private static boolean matchesAny(List<PathMatcher> matchers, Path file, String name) {
        Path nameOnly = Path.of(name);
        for (PathMatcher m : matchers) {
            if (m.matches(nameOnly) || m.matches(file.getFileName())) return true;
        }
        return false;
    }

    private static boolean isHidden(Path path, String name) {
        if (name.startsWith(".")) return true;
        try {
            return Files.exists(path) && Files.isHidden(path);
        } catch (IOException e) {
            return false;
        }
    }

    private static List<PathMatcher> compileGlobs(List<String> globs) {
        List<PathMatcher> out = new ArrayList<>();
        if (globs == null) return out;
        var fs = java.nio.file.FileSystems.getDefault();
        for (String g : globs) {
            if (g == null || g.isBlank()) continue;
            try {
                out.add(fs.getPathMatcher("glob:" + g.trim()));
            } catch (Exception e) {
                log.warn("Ignoring invalid glob '{}': {}", g, e.getMessage());
            }
        }
        return out;
    }

    private static String fileName(Path p) {
        Path n = p.getFileName();
        return n != null ? n.toString() : "";
    }

    /** Parse a comma-separated config value into a trimmed list (skips blanks). */
    public static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
