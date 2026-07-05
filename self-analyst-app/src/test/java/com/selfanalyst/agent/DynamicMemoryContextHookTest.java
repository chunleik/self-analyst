package com.selfanalyst.agent;

import com.selfanalyst.i18n.Lang;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DynamicMemoryContextHookTest {

    @Test
    void injectsFreshMemoryAsTransientSystemMessageWithoutMutatingHistory() {
        DynamicMemoryContextHook hook = new DynamicMemoryContextHook(
                Lang.ZH, () -> "## 长期记忆\n- [preference] 用户偏好中文交流。");
        Msg system = Msg.builder().name("system").role(MsgRole.SYSTEM).textContent("base").build();
        Msg user = Msg.builder().name("user").role(MsgRole.USER).textContent("你好").build();
        List<Msg> original = List.of(system, user);

        List<Msg> injected = hook.inject(original);

        assertEquals(List.of(system, user), original);
        assertEquals(3, injected.size());
        assertEquals(system, injected.get(0));
        assertEquals(MsgRole.SYSTEM, injected.get(1).getRole());
        assertEquals("memory_context", injected.get(1).getName());
        assertTrue(injected.get(1).getTextContent().contains("用户偏好中文交流。"));
        assertEquals(user, injected.get(2));
    }
}
