package com.selfanalyst.events.watcher.platform;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;

public class WindowsWindowTracker implements WindowTracker {

    @Override
    public String getActiveApp() {
        try {
            HWND hwnd = User32.INSTANCE.GetForegroundWindow();
            if (hwnd == null) return "unknown";

            IntByReference pidRef = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef);
            int pid = pidRef.getValue();

            // Attempt to get process command via ProcessHandle API
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

    @Override
    public String getActiveTitle() {
        try {
            HWND hwnd = User32.INSTANCE.GetForegroundWindow();
            if (hwnd == null) return "unknown";

            char[] buffer = new char[1024];
            int len = User32.INSTANCE.GetWindowText(hwnd, buffer, buffer.length);
            if (len > 0) {
                return new String(buffer, 0, len).trim();
            }
            return "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}
