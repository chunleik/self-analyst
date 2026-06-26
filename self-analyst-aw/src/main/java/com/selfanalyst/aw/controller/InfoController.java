package com.selfanalyst.aw.controller;

import com.selfanalyst.aw.model.ServerInfo;
import io.javalin.http.Context;

public class InfoController {
    private final ServerInfo info = ServerInfo.create();

    public void handle(Context ctx) {
        ctx.json(info.toMap());
    }
}
