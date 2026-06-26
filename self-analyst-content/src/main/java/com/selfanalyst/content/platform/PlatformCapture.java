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

    /**
     * Create a ContentCapture configured for this platform.
     */
    ContentCapture createCapture();

    /**
     * Return the executable name of the foreground window's process.
     */
    String getActiveAppName();

    /**
     * Return the title of the foreground window.
     */
    String getActiveWindowTitle();

    /**
     * Return a neutral handle for the foreground window, or {@code 0} if none.
     * On Windows this is the HWND numeric value.
     */
    long getForegroundWindow();
}
