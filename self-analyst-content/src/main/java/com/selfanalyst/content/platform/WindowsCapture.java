package com.selfanalyst.content.platform;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;

/**
 * Windows implementation of foreground-window metadata capture using JNA User32.
 */
public class WindowsCapture implements PlatformCapture {

    @Override
    public ForegroundWindow getForegroundWindowInfo() {
        try {
            HWND hwnd = User32.INSTANCE.GetForegroundWindow();
            if (hwnd == null) return ForegroundWindow.none();

            long handle = com.sun.jna.Pointer.nativeValue(hwnd.getPointer());
            return new ForegroundWindow(handle, appName(hwnd), windowTitle(hwnd));
        } catch (Exception e) {
            return ForegroundWindow.none();
        }
    }

    private static String appName(HWND hwnd) {
        try {
            IntByReference pidRef = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef);
            int pid = pidRef.getValue();

            return ProcessHandle.of(pid)
                .flatMap(ph -> ph.info().command())
                .map(cmd -> {
                    int idx = cmd.lastIndexOf('\\');
                    return idx >= 0 ? cmd.substring(idx + 1) : cmd;
                })
                .orElse("unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String windowTitle(HWND hwnd) {
        try {
            char[] buffer = new char[1024];
            int len = User32.INSTANCE.GetWindowText(hwnd, buffer, buffer.length);
            if (len > 0) {
                return new String(buffer, 0, len).trim();
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

}
