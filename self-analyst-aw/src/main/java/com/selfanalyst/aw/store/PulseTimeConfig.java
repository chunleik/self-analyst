package com.selfanalyst.aw.store;

public record PulseTimeConfig(int defaultPulsetimeSeconds) {

    public static final PulseTimeConfig DEFAULT = new PulseTimeConfig(120);

    public int pulsetime() {
        return defaultPulsetimeSeconds;
    }
}
