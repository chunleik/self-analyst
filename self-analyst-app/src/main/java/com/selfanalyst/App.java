package com.selfanalyst;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
            log.info("SelfAnalyst 已启动 (http://localhost:5700)");

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
}
