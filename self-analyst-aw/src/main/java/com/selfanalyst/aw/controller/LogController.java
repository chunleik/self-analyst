package com.selfanalyst.aw.controller;

import com.selfanalyst.aw.log.ServerLog;
import io.javalin.http.Context;
import java.util.Map;

public class LogController {
    private final ServerLog log;

    public LogController(ServerLog log) {
        this.log = log;
    }

    public void handle(Context ctx) {
        ctx.json(Map.of("log", log.getEntries()));
    }
}
