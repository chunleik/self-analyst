package com.selfanalyst.file;

import org.eclipse.jgit.ignore.FastIgnoreRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Validated immutable configuration for metadata-only file filtering. */
public record FileFilterConfig(
        boolean allowAllExtensions,
        Set<String> allowedExtensions,
        Set<String> excludedDirectoryNames,
        List<String> excludedGlobs,
        long maxFileSizeBytes,
        boolean respectGitIgnore) {

    public static final List<String> DEFAULT_EXTENSIONS = List.of(
            "doc", "docx", "docm",
            "xls", "xlsx", "xlsm", "xlsb",
            "ppt", "pptx", "pptm",
            "pps", "ppsx", "ppsm",
            "pot", "potx", "potm",
            "md", "markdown");

    public static final String DEFAULT_EXTENSIONS_CSV = String.join(",", DEFAULT_EXTENSIONS);

    private static final Set<String> BUILT_IN_EXCLUDED_DIRS = Set.of(
            ".git", ".idea", ".vscode", ".gradle", ".mvn",
            "node_modules", "target", "build", "dist", "out", "bin", "obj",
            "__pycache__", "venv", ".venv", "coverage", "bower_components",
            "vendor", "pods");

    private static final Pattern EXTENSION = Pattern.compile("[a-z0-9][a-z0-9+_-]*");

    public FileFilterConfig {
        LinkedHashSet<String> checkedExtensions = new LinkedHashSet<>();
        if (allowedExtensions != null) {
            for (String extension : allowedExtensions) {
                String normalized = normalizeExtension(extension);
                if ("*".equals(normalized)) {
                    throw new IllegalArgumentException("通配符只能通过 allowAllExtensions 表达");
                }
                checkedExtensions.add(normalized);
            }
        }
        if (allowAllExtensions && !checkedExtensions.isEmpty()) {
            throw new IllegalArgumentException("扩展名通配符 * 不能与其他扩展名同时配置");
        }
        allowedExtensions = Set.copyOf(checkedExtensions);

        LinkedHashSet<String> checkedDirs = new LinkedHashSet<>(BUILT_IN_EXCLUDED_DIRS);
        if (excludedDirectoryNames != null) {
            for (String directory : excludedDirectoryNames) {
                checkedDirs.add(normalizeDirectoryName(directory));
            }
        }
        excludedDirectoryNames = Set.copyOf(checkedDirs);

        List<String> checkedGlobs = new ArrayList<>();
        if (excludedGlobs != null) {
            for (String glob : excludedGlobs) checkedGlobs.add(validateGlob(glob));
        }
        excludedGlobs = List.copyOf(checkedGlobs);
        if (maxFileSizeBytes < 0) throw new IllegalArgumentException("文件大小上限不能为负数");
    }

    public static FileFilterConfig defaults() {
        return parse(0, List.of(), List.of(), DEFAULT_EXTENSIONS, true);
    }

    public static FileFilterConfig parse(long maxFileSizeKb,
                                         List<String> excludeDirs,
                                         List<String> excludeGlobs,
                                         List<String> extensions,
                                         boolean respectGitIgnore) {
        if (maxFileSizeKb < 0) {
            throw new IllegalArgumentException("文件大小上限不能为负数");
        }
        LinkedHashSet<String> normalizedExtensions = new LinkedHashSet<>();
        boolean allowAll = false;
        int wildcardCount = 0;
        if (extensions != null) {
            for (String raw : extensions) {
                String extension = normalizeExtension(raw);
                if ("*".equals(extension)) {
                    allowAll = true;
                    wildcardCount++;
                } else {
                    normalizedExtensions.add(extension);
                }
            }
        }
        if (wildcardCount > 1) {
            throw new IllegalArgumentException("扩展名通配符 * 只能配置一次");
        }
        if (allowAll && !normalizedExtensions.isEmpty()) {
            throw new IllegalArgumentException("扩展名通配符 * 不能与其他扩展名同时配置");
        }

        LinkedHashSet<String> normalizedDirs = new LinkedHashSet<>(BUILT_IN_EXCLUDED_DIRS);
        if (excludeDirs != null) {
            for (String raw : excludeDirs) normalizedDirs.add(normalizeDirectoryName(raw));
        }

        List<String> normalizedGlobs = new ArrayList<>();
        if (excludeGlobs != null) {
            for (String raw : excludeGlobs) normalizedGlobs.add(validateGlob(raw));
        }

        final long maxFileSizeBytes;
        try {
            maxFileSizeBytes = Math.multiplyExact(maxFileSizeKb, 1024L);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("文件大小上限过大", overflow);
        }
        return new FileFilterConfig(allowAll, normalizedExtensions, normalizedDirs,
                normalizedGlobs, maxFileSizeBytes, respectGitIgnore);
    }

    public boolean hasAllowedExtensions() {
        return allowAllExtensions || !allowedExtensions.isEmpty();
    }

    public boolean allowsExtension(String extension) {
        if (allowAllExtensions) return true;
        if (extension == null) return false;
        return allowedExtensions.contains(extension.toLowerCase(Locale.ROOT));
    }

    public static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<String> values = Arrays.stream(csv.split(",", -1)).map(String::trim).toList();
        if (values.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("逗号分隔配置中不能包含空项");
        }
        return values;
    }

    private static String normalizeExtension(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("扩展名不能为空");
        }
        String extension = raw.trim().toLowerCase(Locale.ROOT);
        if (extension.startsWith(".")) extension = extension.substring(1);
        if ("*".equals(extension)) return extension;
        if (!EXTENSION.matcher(extension).matches()) {
            throw new IllegalArgumentException("无效的文件扩展名: " + raw);
        }
        return extension;
    }

    private static String normalizeDirectoryName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("排除目录名不能为空");
        }
        String name = raw.trim().toLowerCase(Locale.ROOT);
        if (name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("排除目录必须是单个目录名: " + raw);
        }
        return name;
    }

    private static String validateGlob(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("排除 glob 不能为空");
        }
        String glob = raw.trim().replace('\\', '/');
        if (glob.startsWith("!") || glob.startsWith("#") || glob.startsWith("/")
                || glob.matches("^[A-Za-z]:/.*")
                || Arrays.asList(glob.split("/", -1)).contains("..")) {
            throw new IllegalArgumentException("排除 glob 必须是非否定的根目录相对模式: " + raw);
        }
        try {
            java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + glob);
        } catch (RuntimeException invalidGlob) {
            throw new IllegalArgumentException("无效的排除 glob: " + raw, invalidGlob);
        }
        FastIgnoreRule rule = new FastIgnoreRule(glob);
        if (rule.isEmpty()) throw new IllegalArgumentException("无效的排除 glob: " + raw);
        return glob;
    }
}
