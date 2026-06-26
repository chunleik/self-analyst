package com.selfanalyst.aw.watcher.platform;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Optional;

public class MacAfkTracker implements AfkTracker {

    @Override
    public long getIdleTimeMillis() {
        // Use ioreg to query HID idle time (nanoseconds), convert to milliseconds
        Optional<String> result = exec(
            "ioreg", "-c", "IOHIDSystem",
            "-r", "-d", "1"
        );
        if (result.isPresent()) {
            try {
                // Parse the HIDIdleTime property from ioreg output
                String output = result.get();
                String[] lines = output.split("\n");
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("\"HIDIdleTime\"")) {
                        // Format: "HIDIdleTime" = <data value>
                        // or numeric format
                        int eqIdx = trimmed.indexOf('=');
                        if (eqIdx >= 0) {
                            String val = trimmed.substring(eqIdx + 1).trim();
                            // Try parsing as decimal number (nanoseconds)
                            long nanos = Long.parseLong(val.replaceAll("[^0-9]", ""));
                            return nanos / 1_000_000L;
                        }
                    }
                }
            } catch (NumberFormatException e) {
                // parsing failed, fall through
            }
        }
        return 0;
    }

    private Optional<String> exec(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                return sb.length() > 0 ? Optional.of(sb.toString()) : Optional.empty();
            }
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
