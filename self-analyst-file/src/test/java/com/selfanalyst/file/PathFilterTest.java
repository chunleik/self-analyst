package com.selfanalyst.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PathFilterTest {

    private PathFilter filter(long maxKb) {
        return new PathFilter(maxKb, List.of(), List.of(), List.of());
    }

    @Test
    void blacklistedDirsExcluded(@TempDir Path tmp) throws Exception {
        Path nm = Files.createDirectories(tmp.resolve("node_modules"));
        Path inside = Files.writeString(nm.resolve("a.js"), "x");
        Path git = Files.createDirectories(tmp.resolve(".git"));
        assertTrue(filter(1024).isExcludedDir(nm));
        assertTrue(filter(1024).isExcludedDir(git));
        assertTrue(filter(1024).isExcludedFile(inside), "file inside node_modules excluded");
    }

    @Test
    void sensitiveFilesExcluded(@TempDir Path tmp) throws Exception {
        Path pem = Files.writeString(tmp.resolve("server.pem"), "x");
        Path key = Files.writeString(tmp.resolve("private.key"), "x");
        Path keystore = Files.writeString(tmp.resolve("app.keystore"), "x");
        assertTrue(filter(1024).isExcludedFile(pem));
        assertTrue(filter(1024).isExcludedFile(key));
        assertTrue(filter(1024).isExcludedFile(keystore));
    }

    @Test
    void volatileFilesExcluded(@TempDir Path tmp) throws Exception {
        Path log = Files.writeString(tmp.resolve("app.log"), "x");
        Path tmpf = Files.writeString(tmp.resolve("scratch.tmp"), "x");
        Path lock = Files.writeString(tmp.resolve("pkg.lock"), "x");
        assertTrue(filter(1024).isExcludedFile(log));
        assertTrue(filter(1024).isExcludedFile(tmpf));
        assertTrue(filter(1024).isExcludedFile(lock));
    }

    @Test
    void oversizedFileExcluded(@TempDir Path tmp) throws Exception {
        Path big = tmp.resolve("big.txt");
        byte[] data = new byte[3 * 1024]; // 3 KB
        Files.write(big, data);
        assertTrue(filter(1).isExcludedFile(big), "file > 1KB excluded");
        assertFalse(filter(1024).isExcludedFile(big), "file < 1MB allowed");
    }

    @Test
    void hiddenFileExcluded(@TempDir Path tmp) throws Exception {
        Path hidden = Files.writeString(tmp.resolve(".secret"), "x");
        assertTrue(filter(1024).isExcludedFile(hidden));
    }

    @Test
    void ordinaryFileAllowed(@TempDir Path tmp) throws Exception {
        Path ok = Files.writeString(tmp.resolve("notes.md"), "hello");
        assertFalse(filter(1024).isExcludedFile(ok));
    }

    @Test
    void extensionAllowListRestricts(@TempDir Path tmp) throws Exception {
        PathFilter f = new PathFilter(1024, List.of(), List.of(), List.of("md"));
        Path md = Files.writeString(tmp.resolve("a.md"), "x");
        Path txt = Files.writeString(tmp.resolve("a.txt"), "x");
        assertFalse(f.isExcludedFile(md));
        assertTrue(f.isExcludedFile(txt), "non-listed extension excluded");
    }

    @Test
    void customExcludeDirAndGlob(@TempDir Path tmp) throws Exception {
        PathFilter f = new PathFilter(1024, List.of("vendor"), List.of("*.bak"), List.of());
        Path vendor = Files.createDirectories(tmp.resolve("vendor"));
        assertTrue(f.isExcludedDir(vendor));
        Path bak = Files.writeString(tmp.resolve("a.bak"), "x");
        assertTrue(f.isExcludedFile(bak));
    }
}
