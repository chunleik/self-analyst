package com.selfanalyst.content.uia;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** 使用自建无敏感内容窗口验证真实 Windows UIAutomation sidecar。 */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfSystemProperty(named = "selfanalyst.synthetic.uia", matches = "true")
class SyntheticUiaSmokeTest {

    @Test
    void queriesOnlyTheControlledTestWindow() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "当前 Windows 会话不支持创建测试窗口");
        String title = "SelfAnalyst Synthetic UIA " + UUID.randomUUID();
        Frame frame = new Frame(title);
        frame.setSize(360, 120);
        frame.setLocation(-10_000, -10_000);

        try {
            frame.setVisible(true);
            HWND window = waitForWindow(title, Duration.ofSeconds(5));
            assertNotNull(window, "无法取得自建测试窗口的 HWND");
            long handle = Pointer.nativeValue(window.getPointer());
            assertTrue(handle != 0, "自建测试窗口必须具有有效 HWND");

            try (AxSidecarClient client = new AxSidecarClient()) {
                assertTrue(client.isAvailable(), "accessibility sidecar 必须已构建并可解析");
                UiaNode cold = client.query(handle);
                UiaNode warm = client.query(handle);
                assertNotNull(cold, "冷启动 UIA 查询应返回测试窗口树");
                assertNotNull(warm, "复用 sidecar 的 UIA 查询应返回测试窗口树");
                assertTrue(containsName(cold, title), "UIA 树应包含自建测试窗口标题");
                assertTrue(containsName(warm, title), "复用查询不应丢失自建测试窗口标题");
            }
        } finally {
            frame.dispose();
        }
    }

    private static HWND waitForWindow(String title, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        HWND window;
        while ((window = User32.INSTANCE.FindWindow(null, title)) == null
                && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
        }
        return window;
    }

    private static boolean containsName(UiaNode node, String expected) {
        if (node == null) return false;
        if (expected.equals(node.name())) return true;
        for (UiaNode child : node.children()) {
            if (containsName(child, expected)) return true;
        }
        return false;
    }
}
