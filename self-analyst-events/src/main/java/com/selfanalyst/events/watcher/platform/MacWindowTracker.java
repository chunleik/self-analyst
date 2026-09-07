package com.selfanalyst.events.watcher.platform;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Optional;

public class MacWindowTracker implements WindowTracker {

    @Override
    public String getActiveApp() {
        // Try osascript first, fall back to placeholder
        return execOsascript(
            "tell application \"System Events\" to get name of first application process whose frontmost is true"
        ).orElse("macOS-app");
    }

    @Override
    public String getActiveTitle() {
        // Try to get the title of the frontmost window via AppleScript
        return execOsascript(
            "tell application \"System Events\"\n" +
            "  set frontApp to name of first application process whose frontmost is true\n" +
            "end tell\n" +
            "tell application frontApp\n" +
            "  if (count of windows) > 0 then return name of front window\n" +
            "end tell"
        ).orElse("macOS (requires accessibility permission)");
    }

    private Optional<String> execOsascript(String script) {
        try {
            ProcessBuilder pb = new ProcessBuilder("osascript", "-e", script);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && !line.isEmpty()) {
                    return Optional.of(line.trim());
                }
            }
        } catch (Exception e) {
            // osascript not available or failed
        }
        return Optional.empty();
    }
}
