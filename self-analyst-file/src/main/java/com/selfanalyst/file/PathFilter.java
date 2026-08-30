package com.selfanalyst.file;

import org.eclipse.jgit.ignore.FastIgnoreRule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;

/** Root-aware, fail-closed metadata filter shared by scanning and WatchService events. */
public class PathFilter {

    private static final List<FastIgnoreRule> SENSITIVE_RULES = rules(
            ".env", ".env.*", "*.pem", "*.key", "id_rsa*", "*.p12", "*.keystore");
    private static final List<FastIgnoreRule> VOLATILE_RULES = rules(
            "*.log", "*.tmp", "*.temp", "*.lock", "*.swp", "*~",
            "~$*", "*.autosave", "*.bak");

    private final FileFilterConfig config;
    private final List<FastIgnoreRule> excludeGlobRules;
    private final GitIgnoreResolver gitIgnoreResolver;
    private final HiddenProbe hiddenProbe;

    public PathFilter(FileFilterConfig config) {
        this(config, Files::isHidden);
    }

    PathFilter(FileFilterConfig config, HiddenProbe hiddenProbe) {
        this.config = config;
        this.excludeGlobRules = config.excludedGlobs().stream().map(FastIgnoreRule::new).toList();
        this.gitIgnoreResolver = new GitIgnoreResolver(config.respectGitIgnore());
        this.hiddenProbe = hiddenProbe;
    }

    public FilterDecision evaluateDirectory(Path watchRoot, Path directory,
                                            BasicFileAttributes attrs) {
        FilterDecision boundary = boundaryDecision(watchRoot, directory, attrs);
        if (boundary.excluded()) return boundary;

        String name = fileName(directory);
        FilterDecision hidden = hiddenDecision(directory, name);
        if (hidden.excluded()) return hidden;
        if (config.excludedDirectoryNames().contains(normalizeName(name))) {
            return excluded("excluded_directory");
        }
        if (matchesCustomGlob(watchRoot, directory, true)) return excluded("excluded_glob");
        return gitIgnoreDecision(watchRoot, directory, true);
    }

    public FilterDecision evaluateFile(Path watchRoot, Path file, BasicFileAttributes attrs) {
        FilterDecision boundary = boundaryDecision(watchRoot, file, attrs);
        if (boundary.excluded()) return boundary;
        if (!attrs.isRegularFile()) return excluded("invalid_path");

        String name = fileName(file);
        if (name.startsWith(".")) return excluded("hidden");
        if (matchesNameRule(SENSITIVE_RULES, name)) return excluded("sensitive_name");
        if (matchesNameRule(VOLATILE_RULES, name)) return excluded("volatile_name");
        if (!config.allowsExtension(extensionOf(name))) return excluded("extension_not_allowed");

        // Unsupported extensions are rejected using only the already supplied
        // attributes and filename. Expensive ancestor/link checks are reserved
        // for files that could otherwise be collected.
        FilterDecision ancestor = excludedAncestorDecision(watchRoot, file);
        if (ancestor.excluded()) return ancestor;
        FilterDecision hidden = filesystemHiddenDecision(file);
        if (hidden.excluded()) return hidden;
        if (matchesCustomGlob(watchRoot, file, false)) return excluded("excluded_glob");
        if (config.maxFileSizeBytes() > 0 && attrs.size() > config.maxFileSizeBytes()) {
            return excluded("oversized");
        }
        return gitIgnoreDecision(watchRoot, file, false);
    }

    public boolean hasAllowedExtensions() {
        return config.hasAllowedExtensions();
    }

    public void invalidateIgnoreRules(Path directory) {
        gitIgnoreResolver.invalidate(directory);
    }

    public boolean respectsGitIgnore() {
        return gitIgnoreResolver.enabled();
    }

    private FilterDecision excludedAncestorDecision(Path watchRoot, Path file) {
        Path root = normalize(watchRoot);
        for (Path parent = normalize(file).getParent(); parent != null && parent.startsWith(root);
             parent = parent.equals(root) ? null : parent.getParent()) {
            try {
                BasicFileAttributes attrs = Files.readAttributes(
                        parent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attrs.isSymbolicLink() || attrs.isOther() || Files.isSymbolicLink(parent)) {
                    return excluded("symbolic_link");
                }
                if (!attrs.isDirectory()) return excluded("invalid_path");
            } catch (IOException accessError) {
                return indeterminate("path_access_error");
            }
            if (parent.equals(root)) continue;
            String name = fileName(parent);
            FilterDecision hidden = hiddenDecision(parent, name);
            if (hidden.excluded()) return hidden;
            if (config.excludedDirectoryNames().contains(normalizeName(name))) {
                return excluded("excluded_directory");
            }
            if (matchesCustomGlob(root, parent, true)) return excluded("excluded_glob");
        }
        return included();
    }

    private FilterDecision boundaryDecision(Path watchRoot, Path candidate,
                                            BasicFileAttributes attrs) {
        if (watchRoot == null || candidate == null || attrs == null) return excluded("invalid_path");
        Path root = normalize(watchRoot);
        Path path = normalize(candidate);
        if (!path.startsWith(root)) return excluded("outside_watch_root");
        if (attrs.isSymbolicLink() || attrs.isOther() || Files.isSymbolicLink(path)) {
            return excluded("symbolic_link");
        }
        return included();
    }

    private FilterDecision gitIgnoreDecision(Path watchRoot, Path candidate, boolean directory) {
        try {
            return gitIgnoreResolver.isIgnored(watchRoot, candidate, directory)
                    ? excluded("gitignore") : included();
        } catch (IOException | RuntimeException error) {
            return indeterminate("gitignore_error");
        }
    }

    private boolean matchesCustomGlob(Path watchRoot, Path candidate, boolean directory) {
        Path root = normalize(watchRoot);
        Path path = normalize(candidate);
        if (!path.startsWith(root) || path.equals(root)) return false;
        String relative = root.relativize(path).toString().replace('\\', '/');
        String basename = fileName(path);
        for (FastIgnoreRule rule : excludeGlobRules) {
            boolean directoryTreeMatch = directory
                    && rule.isMatch(relative + "/__selfanalyst_descendant__", false);
            if ((rule.isMatch(relative, directory) || rule.isMatch(basename, directory)
                    || directoryTreeMatch)
                    && rule.getResult()) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesNameRule(List<FastIgnoreRule> rules, String name) {
        for (FastIgnoreRule rule : rules) {
            if (rule.isMatch(name, false) && rule.getResult()) return true;
        }
        return false;
    }

    private FilterDecision hiddenDecision(Path path, String name) {
        if (name.startsWith(".")) return excluded("hidden");
        return filesystemHiddenDecision(path);
    }

    private FilterDecision filesystemHiddenDecision(Path path) {
        try {
            return hiddenProbe.isHidden(path) ? excluded("hidden") : included();
        } catch (IOException | RuntimeException error) {
            return indeterminate("path_access_error");
        }
    }

    private static List<FastIgnoreRule> rules(String... patterns) {
        return java.util.Arrays.stream(patterns).map(FastIgnoreRule::new).toList();
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name != null ? name.toString() : "";
    }

    public static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static FilterDecision included() {
        return new FilterDecision(false, "included", true);
    }

    private static FilterDecision excluded(String reason) {
        return new FilterDecision(true, reason, true);
    }

    private static FilterDecision indeterminate(String reason) {
        return new FilterDecision(true, reason, false);
    }

    public record FilterDecision(boolean excluded, String reason, boolean determinate) {}

    @FunctionalInterface
    interface HiddenProbe {
        boolean isHidden(Path path) throws IOException;
    }
}
