package com.selfanalyst.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PathFilterTest {

    private static PathFilter filter(long maxKb, List<String> dirs,
                                     List<String> globs, List<String> extensions,
                                     boolean respectGitIgnore) {
        return new PathFilter(FileFilterConfig.parse(
                maxKb, dirs, globs, extensions, respectGitIgnore));
    }

    private static PathFilter filter(List<String> extensions) {
        return filter(0, List.of(), List.of(), extensions, false);
    }

    @Test
    void builtInAndConfiguredDirectoryNamesAreCaseInsensitive(@TempDir Path root) throws Exception {
        Path nodeModules = Files.createDirectory(root.resolve("Node_Modules"));
        Path vendor = Files.createDirectory(root.resolve("VENDOR"));
        Path similar = Files.createDirectory(root.resolve("my-node_modules-copy"));
        PathFilter filter = filter(0, List.of("vendor"), List.of(), List.of("md"), false);

        assertExcluded(filter.evaluateDirectory(root, nodeModules, attrs(nodeModules)),
                "excluded_directory");
        assertExcluded(filter.evaluateDirectory(root, vendor, attrs(vendor)),
                "excluded_directory");
        assertFalse(filter.evaluateDirectory(root, similar, attrs(similar)).excluded());
    }

    @Test
    void everyDotPrefixedToolDirectoryIsSkipped(@TempDir Path root) throws Exception {
        PathFilter filter = filter(List.of("md"));
        int index = 0;
        for (String name : List.of(".git", ".idea", ".agent", ".agents", ".codeGraph",
                ".codegraph", ".next", ".turbo", ".pnpm-store")) {
            Path parent = Files.createDirectory(root.resolve("case-" + index++));
            Path directory = Files.createDirectory(parent.resolve(name));
            assertExcluded(filter.evaluateDirectory(root, directory, attrs(directory)), "hidden");
        }
    }

    @Test
    void defaultConfigAllowsOfficeAndMarkdownOnly(@TempDir Path root) throws Exception {
        PathFilter filter = new PathFilter(FileFilterConfig.defaults());
        Path docx = Files.writeString(root.resolve("report.DOCX"), "x");
        Path xlsm = Files.writeString(root.resolve("budget.xlsm"), "x");
        Path pptm = Files.writeString(root.resolve("slides.pptm"), "x");
        Path md = Files.writeString(root.resolve("notes.md"), "x");
        Path txt = Files.writeString(root.resolve("notes.txt"), "x");

        assertFalse(filter.evaluateFile(root, docx, attrs(docx)).excluded());
        assertFalse(filter.evaluateFile(root, xlsm, attrs(xlsm)).excluded());
        assertFalse(filter.evaluateFile(root, pptm, attrs(pptm)).excluded());
        assertFalse(filter.evaluateFile(root, md, attrs(md)).excluded());
        assertExcluded(filter.evaluateFile(root, txt, attrs(txt)), "extension_not_allowed");
    }

    @Test
    void extensionConfigurationIsNormalizedAndFailClosed(@TempDir Path root) throws Exception {
        Path md = Files.writeString(root.resolve("a.MD"), "x");
        Path txt = Files.writeString(root.resolve("a.txt"), "x");

        PathFilter explicit = filter(List.of(".Md"));
        assertFalse(explicit.evaluateFile(root, md, attrs(md)).excluded());
        assertTrue(explicit.evaluateFile(root, txt, attrs(txt)).excluded());

        PathFilter empty = filter(List.of());
        assertFalse(empty.hasAllowedExtensions());
        assertTrue(empty.evaluateFile(root, md, attrs(md)).excluded());

        PathFilter wildcard = filter(List.of("*"));
        assertTrue(wildcard.hasAllowedExtensions());
        assertFalse(wildcard.evaluateFile(root, txt, attrs(txt)).excluded());
    }

    @Test
    void unsupportedExtensionIsRejectedBeforeExpensiveFilesystemProbes(@TempDir Path root)
            throws Exception {
        Path js = Files.writeString(root.resolve("bundle.js"), "x");
        PathFilter filter = new PathFilter(
                FileFilterConfig.parse(0, List.of(), List.of(), List.of("md"), true),
                path -> { throw new java.io.IOException("probe must not run"); });

        assertExcluded(filter.evaluateFile(root, js, attrs(js)), "extension_not_allowed");
    }

    @Test
    void invalidExtensionAndGlobConfigurationsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> FileFilterConfig.parse(0, List.of(), List.of(), List.of("md", "*"), false));
        assertThrows(IllegalArgumentException.class,
                () -> FileFilterConfig.parse(0, List.of(), List.of(), List.of("*", "*"), false));
        assertThrows(IllegalArgumentException.class,
                () -> FileFilterConfig.parse(0, List.of(), List.of(), List.of("../md"), false));
        assertThrows(IllegalArgumentException.class,
                () -> FileFilterConfig.parse(0, List.of(), List.of("[broken"), List.of("md"), false));
        assertThrows(IllegalArgumentException.class,
                () -> FileFilterConfig.parse(-1, List.of(), List.of(), List.of("md"), false));
    }

    @Test
    void rootRelativeGlobAndBasenameCompatibilityBothWork(@TempDir Path root) throws Exception {
        Path generated = Files.createDirectories(root.resolve("packages/app/generated"));
        Path ordinary = Files.createDirectories(root.resolve("packages/app/source"));
        Path privateFile = Files.writeString(ordinary.resolve("plan.private"), "x");
        PathFilter filter = filter(0, List.of(),
                List.of("**/generated/**", "*.private"), List.of("md", "private"), false);

        assertExcluded(filter.evaluateDirectory(root, generated, attrs(generated)), "excluded_glob");
        assertFalse(filter.evaluateDirectory(root, ordinary, attrs(ordinary)).excluded());
        assertExcluded(filter.evaluateFile(root, privateFile, attrs(privateFile)), "excluded_glob");
    }

    @Test
    void sensitiveAndOfficeTemporaryFilesAreExcludedBeforeExtensionCheck(@TempDir Path root)
            throws Exception {
        PathFilter filter = filter(List.of("docx", "xlsx", "pem", "bak"));
        Path temporary = Files.writeString(root.resolve("~$计划.xlsx"), "x");
        Path autosave = Files.writeString(root.resolve("draft.autosave"), "x");
        Path backup = Files.writeString(root.resolve("report.bak"), "x");
        Path pem = Files.writeString(root.resolve("server.pem"), "x");

        assertExcluded(filter.evaluateFile(root, temporary, attrs(temporary)), "volatile_name");
        assertExcluded(filter.evaluateFile(root, autosave, attrs(autosave)), "volatile_name");
        assertExcluded(filter.evaluateFile(root, backup, attrs(backup)), "volatile_name");
        assertExcluded(filter.evaluateFile(root, pem, attrs(pem)), "sensitive_name");
    }

    @Test
    void zeroSizeCapAllowsLargeMetadataOnlyFiles(@TempDir Path root) throws Exception {
        Path big = root.resolve("big.docx");
        Files.write(big, new byte[3 * 1024]);

        PathFilter capped = filter(1, List.of(), List.of(), List.of("docx"), false);
        PathFilter uncapped = filter(0, List.of(), List.of(), List.of("docx"), false);
        assertExcluded(capped.evaluateFile(root, big, attrs(big)), "oversized");
        assertFalse(uncapped.evaluateFile(root, big, attrs(big)).excluded());
    }

    @Test
    void outsideRootAndSpecialFilesystemNodesAreRejected(@TempDir Path root, @TempDir Path outside)
            throws Exception {
        Path outsideFile = Files.writeString(outside.resolve("outside.md"), "x");
        Path inside = root.resolve("link.md");
        PathFilter filter = filter(List.of("md"));

        assertExcluded(filter.evaluateFile(root, outsideFile, attrs(outsideFile)),
                "outside_watch_root");
        assertExcluded(filter.evaluateFile(root, inside, specialAttrs(true, false)),
                "symbolic_link");
        assertExcluded(filter.evaluateFile(root, inside, specialAttrs(false, true)),
                "symbolic_link");
    }

    @Test
    void ancestorSymbolicLinkIsRejected(@TempDir Path root, @TempDir Path outside)
            throws Exception {
        Path target = Files.writeString(outside.resolve("outside.md"), "x");
        Path link = root.resolve("linked");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException denied) {
            Assumptions.abort("当前文件系统不允许创建符号链接: " + denied.getMessage());
        }
        Path throughLink = link.resolve(target.getFileName());

        PathFilter.FilterDecision decision = filter(List.of("md")).evaluateFile(
                root, throughLink, attrs(throughLink));

        assertExcluded(decision, "symbolic_link");
    }

    @Test
    void gitIgnoreRulesAreAppliedWithoutCollectingTheRuleFile(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".gitignore"), "draft*.md\n!draft-final.md\ngenerated/\n");
        Path draft = Files.writeString(root.resolve("draft-one.md"), "x");
        Path finalDraft = Files.writeString(root.resolve("draft-final.md"), "x");
        Path generated = Files.createDirectory(root.resolve("generated"));
        PathFilter filter = filter(0, List.of(), List.of(), List.of("md"), true);

        assertExcluded(filter.evaluateFile(root, draft, attrs(draft)), "gitignore");
        assertFalse(filter.evaluateFile(root, finalDraft, attrs(finalDraft)).excluded());
        assertExcluded(filter.evaluateDirectory(root, generated, attrs(generated)), "gitignore");
        Path ruleFile = root.resolve(".gitignore");
        assertExcluded(filter.evaluateFile(root, ruleFile, attrs(ruleFile)), "hidden");
    }

    @Test
    void oversizedGitIgnoreFailsClosed(@TempDir Path root) throws Exception {
        Files.write(root.resolve(".gitignore"), new byte[1024 * 1024 + 1]);
        Path file = Files.writeString(root.resolve("notes.md"), "x");
        PathFilter filter = filter(0, List.of(), List.of(), List.of("md"), true);

        PathFilter.FilterDecision decision = filter.evaluateFile(root, file, attrs(file));
        assertTrue(decision.excluded());
        assertEquals("gitignore_error", decision.reason());
        assertFalse(decision.determinate());
    }

    @Test
    void hiddenAttributeReadFailureIsIndeterminate(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("notes.md"), "x");
        PathFilter filter = new PathFilter(
                FileFilterConfig.parse(0, List.of(), List.of(), List.of("md"), false),
                path -> { throw new java.io.IOException("simulated hidden attribute failure"); });

        PathFilter.FilterDecision decision = filter.evaluateFile(root, file, attrs(file));

        assertTrue(decision.excluded());
        assertEquals("path_access_error", decision.reason());
        assertFalse(decision.determinate());
    }

    private static BasicFileAttributes attrs(Path path) throws Exception {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static void assertExcluded(PathFilter.FilterDecision decision, String reason) {
        assertTrue(decision.excluded(), "expected exclusion reason " + reason);
        assertEquals(reason, decision.reason());
        assertTrue(decision.determinate(), "deterministic exclusion expected");
    }

    private static BasicFileAttributes specialAttrs(boolean symbolicLink, boolean other) {
        return new BasicFileAttributes() {
            private final FileTime zero = FileTime.fromMillis(0);
            @Override public FileTime lastModifiedTime() { return zero; }
            @Override public FileTime lastAccessTime() { return zero; }
            @Override public FileTime creationTime() { return zero; }
            @Override public boolean isRegularFile() { return !symbolicLink && !other; }
            @Override public boolean isDirectory() { return false; }
            @Override public boolean isSymbolicLink() { return symbolicLink; }
            @Override public boolean isOther() { return other; }
            @Override public long size() { return 0; }
            @Override public Object fileKey() { return null; }
        };
    }
}
