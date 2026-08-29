package com.selfanalyst;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CountDownLatch;

public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        log.info("SelfAnalyst 启动中...");
        AppSession session = null;
        CountDownLatch shutdownLatch = new CountDownLatch(1);

        try {
            session = new AppSession();
            session.registerDesktopLifecycle(
                    System.getenv("SELF_ANALYST_DESKTOP_TOKEN"), shutdownLatch::countDown);
            publishDesktopPortIfRequested(session.config().awPort());
            log.info("SelfAnalyst 已启动 (http://localhost:{})", session.config().awPort());

            // Register shutdown hook for clean close on Ctrl+C
            AppSession finalSession = session;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("正在关闭...");
                finalSession.close();
                log.info("已关闭");
                shutdownLatch.countDown();
            }));

            shutdownLatch.await();
            log.info("正在关闭...");
            session.close();
            log.info("已关闭");
        } catch (Exception e) {
            log.error("启动失败: {}", e.getMessage(), e);
            if (session != null) session.close();
            System.exit(1);
        }
    }

    private static void publishDesktopPortIfRequested(int port) throws IOException {
        String target = System.getenv("SELF_ANALYST_DESKTOP_PORT_FILE");
        if (target == null || target.isBlank()) {
            return;
        }
        publishDesktopPort(Path.of(target), port);
    }

    static void publishDesktopPort(Path target, int port) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("Desktop port-file directory does not exist: " + parent);
        }

        Path temporary = Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp");
        boolean moved = false;
        try {
            Files.writeString(temporary, Integer.toString(port), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }
}
