package com.selfanalyst.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GitIgnoreResolverTest {

    @Test
    void nestedRulesOverrideParentRules(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".gitignore"), "*.md\n");
        Path child = Files.createDirectory(root.resolve("child"));
        Files.writeString(child.resolve(".gitignore"), "!keep.md\n");
        Path keep = Files.writeString(child.resolve("keep.md"), "x");
        Path drop = Files.writeString(child.resolve("drop.md"), "x");
        GitIgnoreResolver resolver = new GitIgnoreResolver(true);

        assertFalse(resolver.isIgnored(root, keep, false));
        assertTrue(resolver.isIgnored(root, drop, false));
    }

    @Test
    void nestedNegationCannotReincludeDescendantOfIgnoredDirectory(@TempDir Path root)
            throws Exception {
        Files.writeString(root.resolve(".gitignore"), "blocked/\n");
        Path blocked = Files.createDirectory(root.resolve("blocked"));
        Files.writeString(blocked.resolve(".gitignore"), "!keep.md\n");
        Path keep = Files.writeString(blocked.resolve("keep.md"), "x");

        assertTrue(new GitIgnoreResolver(true).isIgnored(root, keep, false));
    }

    @Test
    void malformedRuleIsSkippedWithoutBlockingValidRules(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".gitignore"), "[broken\ndrop.md\n");
        Path keep = Files.writeString(root.resolve("keep.md"), "x");
        Path drop = Files.writeString(root.resolve("drop.md"), "x");
        GitIgnoreResolver resolver = new GitIgnoreResolver(true);

        assertFalse(resolver.isIgnored(root, keep, false));
        assertTrue(resolver.isIgnored(root, drop, false));
    }

    @Test
    void resolverRejectsAncestorSymbolicLinks(@TempDir Path root, @TempDir Path outside)
            throws Exception {
        Path target = Files.writeString(outside.resolve("notes.md"), "x");
        Path link = root.resolve("linked");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException denied) {
            Assumptions.abort("当前文件系统不允许创建符号链接: " + denied.getMessage());
        }

        assertThrows(java.io.IOException.class,
                () -> new GitIgnoreResolver(true).isIgnored(
                        root, link.resolve(target.getFileName()), false));
    }

    @Test
    void invalidationReloadsChangedRuleFile(@TempDir Path root) throws Exception {
        Path ignore = Files.writeString(root.resolve(".gitignore"), "a.md\n");
        Path a = Files.writeString(root.resolve("a.md"), "x");
        Path b = Files.writeString(root.resolve("b.md"), "x");
        GitIgnoreResolver resolver = new GitIgnoreResolver(true);
        assertTrue(resolver.isIgnored(root, a, false));
        assertFalse(resolver.isIgnored(root, b, false));

        Files.writeString(ignore, "b.md\n");
        resolver.invalidate(root);

        assertFalse(resolver.isIgnored(root, a, false));
        assertTrue(resolver.isIgnored(root, b, false));
    }

    @Test
    void replacingDirectoryCannotReuseItsCachedNoRuleState(@TempDir Path root) throws Exception {
        Path child = Files.createDirectory(root.resolve("child"));
        Path first = Files.writeString(child.resolve("first.md"), "x");
        GitIgnoreResolver resolver = new GitIgnoreResolver(true);
        assertFalse(resolver.isIgnored(root, first, false));

        Files.delete(first);
        Files.delete(child);
        Path replacement = Files.createDirectory(root.resolve("child"));
        Files.writeString(replacement.resolve(".gitignore"), "blocked.md\n");
        Path blocked = Files.writeString(replacement.resolve("blocked.md"), "x");

        assertTrue(resolver.isIgnored(root, blocked, false));
    }

    @Test
    void disabledResolverNeverReadsOrAppliesRules(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".gitignore"), "*.md\n");
        Path file = Files.writeString(root.resolve("a.md"), "x");
        assertFalse(new GitIgnoreResolver(false).isIgnored(root, file, false));
    }

    @Test
    void outsideRootIsRejected(@TempDir Path root, @TempDir Path outside) throws Exception {
        Path file = Files.writeString(outside.resolve("a.md"), "x");
        assertThrows(IllegalArgumentException.class,
                () -> new GitIgnoreResolver(true).isIgnored(root, file, false));
    }

    @Test
    void ruleFileEventsAreRecognizedCaseInsensitively() {
        assertTrue(GitIgnoreResolver.isRuleFile(Path.of(".gitignore")));
        assertTrue(GitIgnoreResolver.isRuleFile(Path.of(".GITIGNORE")));
        assertFalse(GitIgnoreResolver.isRuleFile(Path.of("gitignore")));
    }
}
