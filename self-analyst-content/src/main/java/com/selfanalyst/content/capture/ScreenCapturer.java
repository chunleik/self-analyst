package com.selfanalyst.content.capture;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;

public class ScreenCapturer {

    /** DWMWA_EXTENDED_FRAME_BOUNDS — actual DWM-rendered frame, excluding transparent extensions. */
    private static final int DWMWA_EXTENDED_FRAME_BOUNDS = 9;

    private interface Dwmapi extends StdCallLibrary {
        Dwmapi INSTANCE = Native.load("dwmapi", Dwmapi.class, W32APIOptions.DEFAULT_OPTIONS);
        int DwmGetWindowAttribute(WinDef.HWND hwnd, int dwAttribute,
                                  Pointer pvAttribute, int cbAttribute);
    }

    /** 16-byte RECT compatible with DwmGetWindowAttribute output. */
    public static class DwmRect extends Structure {
        public int left, top, right, bottom;
        @Override protected List<String> getFieldOrder() {
            return List.of("left", "top", "right", "bottom");
        }
    }

    public BufferedImage captureWindow(WinDef.HWND hwnd) {
        // Bail if hwnd is no longer the foreground window — another window may have
        // moved on top, and Robot.createScreenCapture would capture the wrong content.
        if (!hwnd.equals(User32.INSTANCE.GetForegroundWindow())) return null;

        WinUser.WINDOWINFO info = new WinUser.WINDOWINFO();
        if (!User32.INSTANCE.GetWindowInfo(hwnd, info)) return null;
        if ((info.dwStyle & WinUser.WS_MINIMIZE) != 0) return null;

        Rectangle rect = dwmFrameBounds(hwnd);
        if (rect == null) {
            // Fallback: use rcWindow from GetWindowInfo
            int w = info.rcWindow.right - info.rcWindow.left;
            int h = info.rcWindow.bottom - info.rcWindow.top;
            if (w <= 0 || h <= 0) return null;
            rect = new Rectangle(info.rcWindow.left, info.rcWindow.top, w, h);
        }

        try {
            return new Robot().createScreenCapture(rect);
        } catch (AWTException e) {
            return null;
        }
    }

    /**
     * Returns the DWM extended frame bounds (actual visible area on screen).
     * For Chromium/Electron apps like 企业微信, rcWindow may be full-screen while
     * the DWM frame correctly reflects the visible portion.
     * Returns null if DWM is unavailable or returns an empty rect.
     */
    private static Rectangle dwmFrameBounds(WinDef.HWND hwnd) {
        try {
            DwmRect r = new DwmRect();
            int hr = Dwmapi.INSTANCE.DwmGetWindowAttribute(
                    hwnd, DWMWA_EXTENDED_FRAME_BOUNDS, r.getPointer(), r.size());
            if (hr != 0) return null; // S_OK == 0
            r.read();
            int w = r.right - r.left;
            int h = r.bottom - r.top;
            if (w <= 0 || h <= 0) return null;
            return new Rectangle(r.left, r.top, w, h);
        } catch (Exception e) {
            return null;
        }
    }
}
