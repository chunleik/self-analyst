package com.selfanalyst.integrations;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

/** Executes real native round-trip only on Windows, using a temporary synthetic credential. */
@EnabledOnOs(OS.WINDOWS)
class WindowsCredentialStoreNativeTest {
    @Test void nativeRoundTripTargetBindingAndDeletion() throws Exception {
        try (var credentials = new WindowsCredentialStore()) {
            CredentialTarget target = CredentialTarget.mcp("native-test", "https://mcp.example.com/synthetic-test");
            String ref = null;
            try {
                ref = credentials.write(target, "SelfAnalyst-synthetic-test-credential".toCharArray(), "windows");
                char[] read = credentials.read(ref, target).orElseThrow();
                assertArrayEquals("SelfAnalyst-synthetic-test-credential".toCharArray(), read); Arrays.fill(read, '\0');
                assertTrue(credentials.read(ref, CredentialTarget.mcp("native-test", "https://other.example.com/")).isEmpty());
            } finally { if (ref != null) credentials.delete(ref); }
            assertFalse(credentials.configured(ref, target));
        }
    }
}
