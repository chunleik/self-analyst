package com.selfanalyst.events.watcher.platform;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Optional;

public class LinuxAfkTracker implements AfkTracker {

    @Override
    public long getIdleTimeMillis() {
        // Primary method: xprintidle (returns milliseconds)
        Optional<String> result = exec("xprintidle");
        if (result.isPresent()) {
            try {
                long millis = Long.parseLong(result.get().trim());
                if (millis >= 0) return millis;
            } catch (NumberFormatException e) {
                // fall through
            }
        }

        // Fallback: check /dev/input via xinput or use xidletime
        result = exec("xidletime");
        if (result.isPresent()) {
            try {
                long millis = Long.parseLong(result.get().trim());
                if (millis >= 0) return millis;
            } catch (NumberFormatException e) {
                // fall through
            }
        }

        return 0;
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
