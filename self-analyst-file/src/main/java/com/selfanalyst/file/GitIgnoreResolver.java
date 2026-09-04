package com.selfanalyst.file;

import org.eclipse.jgit.ignore.FastIgnoreRule;
import org.eclipse.jgit.ignore.IgnoreNode;
import org.eclipse.jgit.ignore.internal.PathMatcher;
import org.eclipse.jgit.ignore.internal.Strings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cached, metadata-only resolver for nested {@code .gitignore} files.
 * Rule text is parsed in memory and never persisted or sent externally.
 */
public final class GitIgnoreResolver {

    private static final long RECHECK_NANOS = 1_000_000_000L;
    private static final long MAX_RULE_FILE_BYTES = 1024L * 1024L;

    private final boolean enabled;
    private final ConcurrentHashMap<Path, CachedRules> rulesByDirectory =
            new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();

    public GitIgnoreResolver(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isIgnored(Path watchRoot, Path candidate, boolean directory) throws IOException {
        if (!enabled) return false;
        Path root = normalize(watchRoot);
        Path path = normalize(candidate);
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("路径不在监控根目录内: " + candidate);
        }
        if (path.equals(root)) return false;

        List<DirectoryIdentity> before = inspectSafeDirectoryChain(root, path.getParent());
        Map<Path, DirectoryIdentity> identities = new LinkedHashMap<>();
        before.forEach(identity -> identities.put(identity.path(), identity));
        boolean ignored = false;
        Path currentAncestor = root;
        for (Path component : root.relativize(path.getParent())) {
            currentAncestor = currentAncestor.resolve(component);
            if (Boolean.TRUE.equals(checkRules(root, currentAncestor, true, identities))) {
                // Git does not allow a rule in an ignored directory to re-include
                // one of that directory's descendants.
                ignored = true;
                break;
            }
        }
        if (!ignored) {
            ignored = Boolean.TRUE.equals(checkRules(root, path, directory, identities));
        }
        List<DirectoryIdentity> after = inspectSafeDirectoryChain(root, path.getParent());
        if (!before.equals(after)) {
            throw new IOException("路径目录链在过滤期间发生变化");
        }
        return ignored;
    }

    private Boolean checkRules(Path root, Path path, boolean directory,
                               Map<Path, DirectoryIdentity> identities) throws IOException {
        for (Path current = path.getParent(); current != null && current.startsWith(root);
             current = current.equals(root) ? null : current.getParent()) {
            DirectoryIdentity identity = identities.get(current);
            if (identity == null) throw new IOException("缺少安全目录身份: " + current);
            List<FastIgnoreRule> rules = rulesFor(current, identity);
            if (rules.isEmpty()) continue;
            String relative = toRepositoryPath(current.relativize(path));
            Boolean ignored = new IgnoreNode(rules).checkIgnored(relative, directory);
            if (ignored != null) return ignored;
        }
        return null;
    }

    public void invalidate(Path directory) {
        if (directory == null) return;
        generation.incrementAndGet();
        Path normalized = normalize(directory);
        rulesByDirectory.keySet().removeIf(path -> path.startsWith(normalized));
    }

    public void invalidateAll() {
        generation.incrementAndGet();
        rulesByDirectory.clear();
    }

    public boolean enabled() {
        return enabled;
    }

    public static boolean isRuleFile(Path path) {
        return path != null && path.getFileName() != null
                && ".gitignore".equalsIgnoreCase(path.getFileName().toString());
    }

    private List<FastIgnoreRule> rulesFor(Path directory,
                                          DirectoryIdentity expectedDirectory) throws IOException {
        while (true) {
            long expectedGeneration = generation.get();
            long now = System.nanoTime();
            CachedRules cached = rulesByDirectory.get(directory);
            if (cached != null && cached.generation() == expectedGeneration
                    && cached.directoryIdentity().equals(expectedDirectory)
                    && now - cached.checkedAtNanos() < RECHECK_NANOS
                    && (cached.state().exists()
                            || Files.notExists(directory.resolve(".gitignore"),
                                    LinkOption.NOFOLLOW_LINKS))) {
                return cached.rules();
            }
            try {
                CachedRules refreshed = rulesByDirectory.compute(directory, (path, previous) -> {
                    try {
                        verifyDirectoryIdentity(expectedDirectory);
                        RuleFileState state = inspect(path.resolve(".gitignore"));
                        if (previous != null && previous.generation() == expectedGeneration
                                && previous.directoryIdentity().equals(expectedDirectory)
                                && previous.sameFileState(state)) {
                            verifyDirectoryIdentity(expectedDirectory);
                            return new CachedRules(expectedDirectory, state, previous.rules(), now,
                                    expectedGeneration);
                        }
                        return new CachedRules(expectedDirectory, state,
                                loadRules(state, expectedDirectory), now, expectedGeneration);
                    } catch (IOException error) {
                        throw new IgnoreLoadFailure(error);
                    }
                });
                if (generation.get() == expectedGeneration) return refreshed.rules();
                rulesByDirectory.remove(directory, refreshed);
            } catch (IgnoreLoadFailure failure) {
                throw failure.cause;
            }
        }
    }

    private static RuleFileState inspect(Path ignoreFile) throws IOException {
        final BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(
                    ignoreFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return new RuleFileState(ignoreFile, false, 0, FileTime.fromMillis(0),
                    FileTime.fromMillis(0), null);
        }
        if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther()) {
            throw new IOException(".gitignore 不是安全的普通文件: " + ignoreFile);
        }
        if (attrs.size() > MAX_RULE_FILE_BYTES) {
            throw new IOException(".gitignore 超过 1 MiB 安全上限: " + ignoreFile);
        }
        return new RuleFileState(ignoreFile, true, attrs.size(), attrs.lastModifiedTime(),
                attrs.creationTime(), fileKey(attrs));
    }

    private static List<FastIgnoreRule> loadRules(RuleFileState state,
                                                  DirectoryIdentity directoryIdentity)
            throws IOException {
        verifyDirectoryIdentity(directoryIdentity);
        if (!state.exists()) {
            verifyDirectoryIdentity(directoryIdentity);
            return List.of();
        }
        byte[] bytes;
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = Files.newByteChannel(state.path(), options)) {
            bytes = Channels.newInputStream(channel).readNBytes((int) MAX_RULE_FILE_BYTES + 1);
        }
        if (bytes.length > MAX_RULE_FILE_BYTES) {
            throw new IOException(".gitignore 超过 1 MiB 安全上限: " + state.path());
        }
        RuleFileState afterRead = inspect(state.path());
        if (!state.sameIdentity(afterRead)) {
            throw new IOException(".gitignore 在读取期间发生变化: " + state.path());
        }
        verifyDirectoryIdentity(directoryIdentity);

        List<FastIgnoreRule> rules = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(
                new String(bytes, StandardCharsets.UTF_8)))) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine && !line.isEmpty() && line.charAt(0) == '\ufeff') {
                    line = line.substring(1);
                }
                firstLine = false;
                if (!isValidRule(line)) continue;
                FastIgnoreRule rule = new FastIgnoreRule(line);
                if (!rule.isEmpty()) rules.add(rule);
            }
        }
        return List.copyOf(rules);
    }

    /**
     * Pre-validates using the same matcher factory as {@link FastIgnoreRule}.
     * This prevents its public constructor from logging malformed rule text.
     */
    private static boolean isValidRule(String original) {
        if (original == null || original.isEmpty() || original.charAt(0) == '#') return false;
        String pattern = original;
        if (pattern.charAt(0) == '!') {
            pattern = pattern.substring(1);
            if (pattern.isEmpty() || "\\".equals(pattern)) return false;
        }
        boolean directoryOnly = Strings.isDirectoryPattern(pattern);
        if (directoryOnly) {
            pattern = Strings.stripTrailing(pattern.stripTrailing(), '/');
            if (pattern.isEmpty() || "\\".equals(pattern)) return false;
        }
        try {
            PathMatcher.createPathMatcher(pattern, '/', directoryOnly);
            return true;
        } catch (RuntimeException | org.eclipse.jgit.errors.InvalidPatternException invalid) {
            return false;
        }
    }

    private static List<DirectoryIdentity> inspectSafeDirectoryChain(Path root, Path target)
            throws IOException {
        if (target == null || !target.startsWith(root)) {
            throw new IOException("候选路径的父目录不在监控根目录内: " + target);
        }
        List<DirectoryIdentity> identities = new ArrayList<>();
        Path current = root;
        identities.add(inspectSafeDirectory(current));
        for (Path component : root.relativize(target)) {
            current = current.resolve(component);
            identities.add(inspectSafeDirectory(current));
        }
        return List.copyOf(identities);
    }

    private static DirectoryIdentity inspectSafeDirectory(Path directory) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(
                directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther()) {
            throw new IOException("路径包含不安全的目录链接: " + directory);
        }
        return new DirectoryIdentity(directory, fileKey(attrs), attrs.creationTime());
    }

    private static void verifyDirectoryIdentity(DirectoryIdentity expected) throws IOException {
        DirectoryIdentity actual = inspectSafeDirectory(expected.path());
        if (!expected.equals(actual)) {
            throw new IOException("目录身份在过滤期间发生变化: " + expected.path());
        }
    }

    private static Path normalize(Path path) {
        if (path == null) throw new IllegalArgumentException("路径不能为空");
        return path.toAbsolutePath().normalize();
    }

    private static String toRepositoryPath(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    private static String fileKey(BasicFileAttributes attrs) {
        Object key = attrs.fileKey();
        return key != null ? key.toString() : null;
    }

    private record RuleFileState(Path path, boolean exists, long size, FileTime modified,
                                 FileTime created, String fileKey) {
        private boolean sameIdentity(RuleFileState other) {
            return exists == other.exists
                    && size == other.size
                    && modified.equals(other.modified)
                    && created.equals(other.created)
                    && Objects.equals(fileKey, other.fileKey);
        }
    }

    private record DirectoryIdentity(Path path, String fileKey, FileTime creationTime) {}

    private record CachedRules(DirectoryIdentity directoryIdentity, RuleFileState state,
                               List<FastIgnoreRule> rules,
                               long checkedAtNanos, long generation) {
        private boolean sameFileState(RuleFileState other) {
            return state.sameIdentity(other);
        }
    }

    private static final class IgnoreLoadFailure extends RuntimeException {
        private final IOException cause;
        private IgnoreLoadFailure(IOException cause) {
            super(cause);
            this.cause = cause;
        }
    }
}
