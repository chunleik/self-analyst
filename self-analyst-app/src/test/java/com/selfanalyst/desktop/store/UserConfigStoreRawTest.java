package com.selfanalyst.desktop.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verbatim raw read/write round-trip (SPEC-CFGUI-TST-003). */
class UserConfigStoreRawTest {

    @Test
    void readRawReturnsEmptyWhenFileMissing(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        assertEquals("", store.readRaw());
    }

    @Test
    void saveRawThenReadRawIsCharForCharFaithful(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        String text = "# 用户配置\n"
                + "\n"
                + "# LLM\n"
                + "llm.model=gpt-4o\n"
                + "llm.api-key=sk-测试密钥\n"
                + "\n"
                + "# 备注：保留注释、空行与键顺序\n";

        store.saveRaw(text);

        // Round-trip must preserve comments, blank lines, ordering and中文 values.
        assertEquals(text, store.readRaw());
    }

    @Test
    void nonAsciiValuesDecodeViaLoadUser(@TempDir Path dir) throws Exception {
        // saveRaw writes UTF-8; loadUser() must read UTF-8 too, so 中文 values
        // are not mojibake at runtime (regression guard for the charset fix).
        UserConfigStore store = new UserConfigStore(dir);
        store.saveRaw("aw.data-dir=D:/数据/中文目录\nllm.model=智谱-glm\n");

        assertEquals("D:/数据/中文目录", store.loadUser().getProperty("aw.data-dir"));
        assertEquals("智谱-glm", store.loadUser().getProperty("llm.model"));
    }
}
