package com.selfanalyst.events.controller;

import com.selfanalyst.events.settings.SettingsManager;
import io.javalin.http.Context;

import java.util.Map;

public class SettingsController {

    private final SettingsManager settings;

    public SettingsController(SettingsManager settings) {
        this.settings = settings;
    }

    public void handle(Context ctx) {
        try {
            ctx.json(settings.getAll());
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to get settings: " + e.getMessage()));
        }
    }
}
