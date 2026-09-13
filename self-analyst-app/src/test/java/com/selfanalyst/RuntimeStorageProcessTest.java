package com.selfanalyst;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeStorageProcessTest {
    @TempDir Path root;

    @Test void productionEntrypointRejectsBeforePublishingOrOpeningStores() throws Exception {
        Path data = Files.createDirectory(root.resolve("data"));
        for (int version : new int[]{0, 2}) {
            Files.writeString(data.resolve("storage-format.json"), "{\"formatVersion\":" + version + "}");
            assertEquals(22, runApplication());
            assertFalse(Files.exists(root.resolve("port.txt")));
            assertFalse(Files.exists(data.resolve("memory")));
            assertFalse(Files.exists(data.resolve("events")));
        }
        Files.writeString(data.resolve("storage-format.json"), "{}");
        assertEquals(23, runApplication());
        Files.delete(data.resolve("storage-format.json"));
        try (var guard = RuntimeStorageGuard.acquire(data, path -> {})) {
            assertEquals(20, runApplication());
        }
    }

    private int runApplication() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), App.class.getName());
        builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(root.resolve("process.log").toFile());
        builder.environment().put("SELF_ANALYST_DESKTOP_PORT_FILE", root.resolve("port.txt").toString());
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            return process.exitValue();
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor(15, TimeUnit.SECONDS);
        }
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            try (var guard = RuntimeStorageGuard.acquire(Path.of(args[0]), path -> {})) {
                Files.writeString(Path.of(args[1]), "ready");
                System.in.read();
            } catch (RuntimeStorageGuard.StorageException e) {
                System.exit(e.failure().exitCode());
            }
        }
    }

    private Process start(Path data, Path ready) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                Probe.class.getName(), data.toString(), ready.toString()).redirectErrorStream(true).start();
    }

    private void awaitReady(Process process, Path ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(ready) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(Files.exists(ready), "child did not obtain lock");
    }

    @Test void competingProcessesAndCrashRelease() throws Exception {
        Path data = root.resolve("data");
        Process first = start(data, root.resolve("first"));
        Process second = null;
        Process next = null;
        try {
            awaitReady(first, root.resolve("first"));
            second = start(data, root.resolve("second"));
            assertTrue(second.waitFor(15, TimeUnit.SECONDS));
            assertEquals(20, second.exitValue());
            assertFalse(Files.exists(root.resolve("second")));
            first.destroyForcibly();
            assertTrue(first.waitFor(15, TimeUnit.SECONDS));
            next = start(data, root.resolve("next"));
            awaitReady(next, root.resolve("next"));
        } finally {
            first.destroyForcibly().waitFor(15, TimeUnit.SECONDS);
            if (second != null) second.destroyForcibly().waitFor(15, TimeUnit.SECONDS);
            if (next != null) next.destroyForcibly().waitFor(15, TimeUnit.SECONDS);
        }
    }

    @Test void separateRootsDoNotContend() throws Exception {
        Process first = start(root.resolve("a"), root.resolve("ready-a"));
        Process second = start(root.resolve("b"), root.resolve("ready-b"));
        try {
            awaitReady(first, root.resolve("ready-a"));
            awaitReady(second, root.resolve("ready-b"));
        } finally {
            first.destroyForcibly().waitFor(15, TimeUnit.SECONDS);
            second.destroyForcibly().waitFor(15, TimeUnit.SECONDS);
        }
    }
}
