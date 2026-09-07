package com.selfanalyst.events.watcher.platform;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Optional;

public class LinuxWindowTracker implements WindowTracker {

    @Override
    public String getActiveApp() {
        // Try to get the window's PID via xdotool, then resolve the process name
        Optional<String> pidStr = exec("xdotool", "getactivewindow", "getwindowpid");
        if (pidStr.isPresent()) {
            try {
                int pid = Integer.parseInt(pidStr.get().trim());
                return ProcessHandle.of(pid)
                    .flatMap(ph -> ph.info().command())
                    .map(cmd -> {
                        int idx = cmd.lastIndexOf('/');
                        return idx >= 0 ? cmd.substring(idx + 1) : cmd;
                    })
                    .orElse("unknown");
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        return "unknown";
    }

    @Override
    public String getActiveTitle() {
        return exec("xdotool", "getactivewindow", "getwindowname")
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .orElse("unknown");
    }

    private Optional<String> exec(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && !line.isEmpty()) {
                    return Optional.of(line.trim());
                }
            }
        } catch (Exception e) {
            // command not available or failed
        }
        return Optional.empty();
    }
}
