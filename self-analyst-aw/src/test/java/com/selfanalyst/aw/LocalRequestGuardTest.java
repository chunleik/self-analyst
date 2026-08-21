package com.selfanalyst.aw;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalRequestGuardTest {

    @Test
    void acceptsOnlyExactLoopbackOrigins() {
        assertTrue(LocalRequestGuard.isAllowedOrigin("http://localhost:5700"));
        assertTrue(LocalRequestGuard.isAllowedOrigin("https://localhost:3000"));
        assertTrue(LocalRequestGuard.isAllowedOrigin("http://127.0.0.1:5700"));
        assertTrue(LocalRequestGuard.isAllowedOrigin("http://[::1]:5700"));

        assertFalse(LocalRequestGuard.isAllowedOrigin("http://localhost.evil.example"));
        assertFalse(LocalRequestGuard.isAllowedOrigin("http://127.0.0.11:5700"));
        assertFalse(LocalRequestGuard.isAllowedOrigin("http://127.0.0.1.evil.example"));
        assertFalse(LocalRequestGuard.isAllowedOrigin("file://localhost/app"));
        assertFalse(LocalRequestGuard.isAllowedOrigin("null"));
    }

    @Test
    void hostMustBeLoopbackAndMatchConfiguredPort() {
        assertTrue(LocalRequestGuard.isAllowedHost("localhost:5700", 5700));
        assertTrue(LocalRequestGuard.isAllowedHost("127.0.0.1:5700", 5700));
        assertTrue(LocalRequestGuard.isAllowedHost("[::1]:5700", 5700));
        assertTrue(LocalRequestGuard.isAllowedHost("localhost", 80));

        assertFalse(LocalRequestGuard.isAllowedHost("localhost.evil.example:5700", 5700));
        assertFalse(LocalRequestGuard.isAllowedHost("127.0.0.11:5700", 5700));
        assertFalse(LocalRequestGuard.isAllowedHost("localhost:5701", 5700));
        assertFalse(LocalRequestGuard.isAllowedHost(null, 5700));
    }

    @Test
    void desktopApisRequireTheLaunchCredentialWhenConfigured() {
        assertTrue(LocalRequestGuard.isProtectedDesktopPath("/desktop/config/raw"));
        assertFalse(LocalRequestGuard.isProtectedDesktopPath("/desktop-ui/index.html"));
        assertFalse(LocalRequestGuard.isProtectedDesktopPath("/desktop/session"));

        assertTrue(LocalRequestGuard.hasDesktopCredential(null, null, null));
        assertTrue(LocalRequestGuard.hasDesktopCredential("secret", "secret", null));
        assertTrue(LocalRequestGuard.hasDesktopCredential("secret", null, "secret"));
        assertFalse(LocalRequestGuard.hasDesktopCredential("secret", "wrong", null));
    }
}
