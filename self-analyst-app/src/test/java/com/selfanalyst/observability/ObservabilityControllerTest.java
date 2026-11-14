package com.selfanalyst.observability;

import static org.junit.jupiter.api.Assertions.*;

import com.selfanalyst.desktop.controller.DesktopObservabilityController;

import io.javalin.Javalin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

class ObservabilityControllerTest {
    @TempDir Path root;

    @Test
    void managedAuthenticationStrictJsonAndNoCredentialReadRoute() throws Exception {
        AtomicBoolean managed = new AtomicBoolean(false);
        var app = Javalin.create(c -> c.showJavalinBanner = false);
        try (var service = new ObservabilityService(root)) {
            app.before(ctx -> ctx.attribute("selfanalyst.managedDesktop", managed.get()));
            new DesktopObservabilityController(service).register(app);
            app.start("127.0.0.1", 0);
            try (var client = HttpClient.newHttpClient()) {
                URI uri = URI.create("http://127.0.0.1:" + app.port() + "/desktop/observability");
                var blocked =
                        client.send(
                                HttpRequest.newBuilder(uri).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
                assertEquals(403, blocked.statusCode());
                managed.set(true);
                var snapshot =
                        client.send(
                                HttpRequest.newBuilder(uri).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
                assertEquals(200, snapshot.statusCode());
                assertFalse(snapshot.body().contains("secretRef"));
                assertEquals("no-store", snapshot.headers().firstValue("Cache-Control").orElse(""));
                for (String invalid :
                        new String[] {
                            "{\"expectedRevision\":0,\"expectedRevision\":1}",
                            "{\"expectedRevision\":0} {}",
                            "{\"expectedRevision\":\"0\"}",
                            "{\"expectedRevision\":0,\"unexpected\":true}"
                        }) {
                    var response =
                            client.send(
                                    HttpRequest.newBuilder(URI.create(uri + "/disable"))
                                            .header("Content-Type", "application/json")
                                            .POST(HttpRequest.BodyPublishers.ofString(invalid))
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString());
                    assertEquals(400, response.statusCode(), invalid);
                    assertFalse(response.body().contains("unexpected"));
                }
                var noRead =
                        client.send(
                                HttpRequest.newBuilder(URI.create(uri + "/credential"))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
                assertNotEquals(200, noRead.statusCode());
            }
        } finally { app.stop(); }
    }
}
