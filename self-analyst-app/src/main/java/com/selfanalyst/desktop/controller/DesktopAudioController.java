package com.selfanalyst.desktop.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.audio.AudioCaptureDiagnostics;
import com.selfanalyst.audio.AudioCaptureManager;
import io.javalin.http.Context;

import java.util.LinkedHashMap;
import java.util.Map;

public class DesktopAudioController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AudioCaptureManager audioCaptureManager;

    public DesktopAudioController(AudioCaptureManager audioCaptureManager) {
        this.audioCaptureManager = audioCaptureManager;
    }

    public void setAudioCapture(Context ctx) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = MAPPER.readValue(ctx.body(), Map.class);
            Object rawEnabled = body.get("enabled");
            if (!(rawEnabled instanceof Boolean enabled)) {
                ctx.status(400).json(Map.of("error", "enabled must be boolean"));
                return;
            }
            ctx.json(applyAudioCapture(enabled));
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", e.getMessage() != null ? e.getMessage() : "Invalid request"));
        }
    }

    Map<String, Object> applyAudioCapture(boolean enabled) {
        if (audioCaptureManager == null) {
            return audioState(new AudioCaptureManager.AudioCaptureState(
                    false, "disabled", AudioCaptureDiagnostics.empty()));
        }
        return audioState(audioCaptureManager.setEnabled(enabled));
    }

    static Map<String, Object> audioState(AudioCaptureManager.AudioCaptureState state) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", state.enabled());
        result.put("status", state.status());
        result.put("diagnostics", state.diagnostics());
        return result;
    }
}
