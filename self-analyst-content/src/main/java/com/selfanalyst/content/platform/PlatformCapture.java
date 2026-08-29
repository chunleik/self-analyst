package com.selfanalyst.content.platform;

import com.selfanalyst.content.capture.ContentCapture;

/**
 * Platform-specific capture setup.
 * <p>
 * Provides factory methods for creating the platform-appropriate
 * ContentCapture, and accessors for the active foreground window.
 * <p>
 * SPEC-AXS-031: the foreground-window accessor exposes a neutral {@code long}
 * handle, never an OS-specific type, so callers stay platform-agnostic.
 */
public interface PlatformCapture {

    /** Foreground identity resolved from one HWND snapshot. */
    record ForegroundWindow(long handle, String app, String title) {
        public static ForegroundWindow none() {
            return new ForegroundWindow(0L, "unknown", "");
        }
    }

    /**
     * Create a ContentCapture configured for this platform.
     */
    ContentCapture createCapture();

    /**
     * Return the executable name of the foreground window's process.
     */
    default String getActiveAppName() {
        return getForegroundWindowInfo().app();
    }

    /**
     * Return the title of the foreground window.
     */
    default String getActiveWindowTitle() {
        return getForegroundWindowInfo().title();
    }

    /**
     * Return a neutral handle for the foreground window, or {@code 0} if none.
     * On Windows this is the HWND numeric value.
     */
    default long getForegroundWindow() {
        return getForegroundWindowInfo().handle();
    }

    /** Resolve handle, process name, and title from the same foreground HWND. */
    ForegroundWindow getForegroundWindowInfo();
}
