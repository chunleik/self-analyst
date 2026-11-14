package com.selfanalyst.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.config.Config;
import com.selfanalyst.desktop.controller.DesktopIntegrationController;
import com.selfanalyst.desktop.store.ChatSessionStore;
import com.selfanalyst.integrations.*;
import com.selfanalyst.integrations.policy.MailSessionPolicy;
import com.selfanalyst.mcp.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DesktopIntegrationControllerTest {
    @TempDir Path root;
    @Test void requiresManagedDesktopEvenInLegacyNoTokenMode() throws Exception {
        var server=new EventServer(root.resolve("events"),0,null);
        try (AutoCloseable cleanup=server::stop; var sessions=new ChatSessionStore(root.resolve("memory"));
             var manager=new McpManager(new IntegrationStore(root),new SessionCredentialStore(),new MailSessionPolicy(sessions),
                     Config.testDefaults(root.resolve("memory")),new PinnedHttpsClient(),new ApprovalRegistry())) {
            new DesktopIntegrationController(manager,()->"https://model.example.com/v1").register(server.app()); server.start(0);
            var response=request(server,"GET","",null,null);
            assertEquals(403,response.statusCode()); assertTrue(response.body().contains("managed_desktop_required"));
            assertEquals(403, request(server, "POST", "/delete",
                    "{\"kind\":\"mcp\",\"id\":\"missing\",\"expectedRevision\":0}", null).statusCode());
        }
    }
    @Test void strictConfigCasWriteOnlyCredentialsAndPortableExport() throws Exception {
        var server=new EventServer(root.resolve("events"),0,"integration-test-token");
        try (AutoCloseable cleanup=server::stop; var sessions=new ChatSessionStore(root.resolve("memory"));
             var manager=new McpManager(new IntegrationStore(root),new SessionCredentialStore(),new MailSessionPolicy(sessions),
                     Config.testDefaults(root.resolve("memory")),new PinnedHttpsClient(),new ApprovalRegistry())) {
            new DesktopIntegrationController(manager,()->"https://model.example.com/v1").register(server.app()); server.start(0);
            assertEquals(401,request(server,"GET","",null,null).statusCode());
            assertEquals(200,request(server,"GET","",null,"integration-test-token").statusCode());
            var account=new IntegrationConfig.MailAccount("qq","qq","12345@qq.com","Synthetic",false,List.of("INBOX"),false,null,"session",0);
            var config=new IntegrationConfig(1,0,List.of(),List.of(account),List.of());
            String body="{\"expectedRevision\":0,\"config\":"+new ObjectMapper().writeValueAsString(config)+"}";
            assertEquals(200,request(server,"PUT","",body,"integration-test-token").statusCode());
            assertEquals(409,request(server,"PUT","",body,"integration-test-token").statusCode());
            var secret=request(server,"POST","/credential","{\"kind\":\"mail\",\"id\":\"qq\",\"secret\":\"synthetic-private-code\",\"persist\":false,\"expectedRevision\":1}","integration-test-token");
            assertEquals(200,secret.statusCode()); assertFalse(secret.body().contains("synthetic-private-code"));
            var exported=request(server,"GET","/export",null,"integration-test-token");
            assertEquals(200,exported.statusCode()); assertFalse(exported.body().contains("synthetic-private-code"));
            assertFalse(exported.body().contains("session:"));
            assertEquals(400,request(server,"POST","/test","{\"kind\":\"mail\",\"id\":\"qq\",\"hostname\":\"127.0.0.1\"}","integration-test-token").statusCode());
            var error=request(server,"POST","/credential","{\"kind\":\"mail\",\"id\":\"missing\",\"secret\":\"synthetic-private-code\",\"persist\":false,\"expectedRevision\":2}","integration-test-token");
            assertEquals(400,error.statusCode()); assertFalse(error.body().contains("synthetic-private-code"));
        }
    }
    @Test void deleteRequiresAuthenticationExactIdentityAndStrictVersionedBody() throws Exception {
        var server = new EventServer(root.resolve("events"), 0, "integration-test-token");
        try (AutoCloseable cleanup = server::stop; var sessions = new ChatSessionStore(root.resolve("memory"));
             var manager = new McpManager(new IntegrationStore(root), new SessionCredentialStore(),
                     new MailSessionPolicy(sessions), Config.testDefaults(root.resolve("memory")),
                     new PinnedHttpsClient(), new ApprovalRegistry())) {
            var one = new IntegrationConfig.McpServer("one", "One", false, "streamable-http",
                    "https://example.com/one", "anonymous", null, "session", IntegrationConfig.Budget.defaults());
            var two = new IntegrationConfig.McpServer("two", "Two", false, "streamable-http",
                    "https://example.com/two", "anonymous", null, "session", IntegrationConfig.Budget.defaults());
            var before = manager.save(0, new IntegrationConfig(1, 0, List.of(one, two), List.of(), List.of()));
            new DesktopIntegrationController(manager, () -> "https://model.example.com/v1").register(server.app());
            server.start(0);
            String valid = "{\"kind\":\"mcp\",\"id\":\"one\",\"expectedRevision\":1}";
            assertEquals(401, request(server, "POST", "/delete", valid, null).statusCode());
            for (String invalid : List.of(
                    valid.replace("\"mcp\"", "\"mail\""),
                    valid.replace("\"one\"", "\"missing\""),
                    valid.replace("\"one\"", "\"legacy-websearch\""),
                    valid.replace("\"mcp\"", "\"legacy\""),
                    valid.replace("\"kind\":\"mcp\",", ""),
                    valid.replace("\"id\":\"one\"", "\"id\":4"),
                    valid.replace(":1}", ":\"1\"}"),
                    valid.replace(":1}", ":-1}"),
                    valid.replace("}", ",\"config\":{}}"),
                    valid.replace("}", ",\"id\":\"two\"}"),
                    valid + " {}")) {
                assertEquals(400, request(server, "POST", "/delete", invalid, "integration-test-token").statusCode(), invalid);
                assertEquals(before, manager.config());
            }
            assertEquals(409, request(server, "POST", "/delete", valid.replace(":1}", ":0}"), "integration-test-token").statusCode());
            var response = request(server, "POST", "/delete", valid, "integration-test-token");
            assertEquals(200, response.statusCode());
            var json = new ObjectMapper().readTree(response.body());
            assertEquals(2, json.path("config").path("revision").asLong());
            assertEquals("two", json.path("config").path("mcpServers").get(0).path("id").asText());
            assertEquals(1, json.path("config").path("mcpServers").size());
            assertFalse(json.path("statuses").has("one"));
            assertEquals(409, request(server, "POST", "/delete", valid, "integration-test-token").statusCode());
            assertEquals(List.of(two), manager.config().mcpServers());
        }
    }

    private static HttpResponse<String> request(EventServer server,String method,String suffix,String body,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.port()+"/desktop/integrations"+suffix));
        if (token!=null) builder.header("X-SelfAnalyst-Token",token);
        if (body!=null) builder.header("Content-Type","application/json");
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
}
