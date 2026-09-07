package com.selfanalyst.events.controller;

import com.selfanalyst.events.model.ServerInfo;
import io.javalin.http.Context;

public class InfoController {
    private final ServerInfo info = ServerInfo.create();

    public void handle(Context ctx) {
        ctx.json(info.toMap());
    }
}
