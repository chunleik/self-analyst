package com.selfanalyst.events.watcher.platform;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinUser.LASTINPUTINFO;

public class WindowsAfkTracker implements AfkTracker {

    @Override
    public long getIdleTimeMillis() {
        try {
            LASTINPUTINFO lii = new LASTINPUTINFO();
            if (User32.INSTANCE.GetLastInputInfo(lii)) {
                int tickCount = Kernel32.INSTANCE.GetTickCount();
                // Handle tick count overflow (wrap-around at ~49.7 days)
                long idle = (long) tickCount - (long) lii.dwTime;
                if (idle < 0) {
                    // Overflow occurred, adjust
                    idle = (long) Integer.MAX_VALUE * 2 - tickCount + lii.dwTime;
                }
                return idle;
            }
        } catch (Exception e) {
            // JNA call failed
        }
        return 0;
    }
}
