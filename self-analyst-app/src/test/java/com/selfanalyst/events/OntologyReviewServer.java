package com.selfanalyst.events;

import com.selfanalyst.desktop.controller.DesktopOntologyController;
import com.selfanalyst.memory.GrowthProfile;
import com.selfanalyst.ontology.*;
import com.selfanalyst.wiki.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Explicit visual review server: isolated synthetic data, no collectors or LLM requests. */
public final class OntologyReviewServer {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("selfanalyst-ontology-review-");
        WikiStore wiki = new WikiStore(root.resolve("wiki.db"));
        GrowthProfile profile = new GrowthProfile();
        profile.getGoals().add(new GrowthProfile.Goal("goal", "建立可追溯的个人工作记录", "", 0, 1, LocalDate.of(2026, 9, 1), true));
        profile.getPatterns().add(new GrowthProfile.KnownPattern("上午进行开发，下午集中复盘", "用户描述的工作习惯", LocalDate.of(2026, 9, 1), 8));
        profile.getLogs().add(new GrowthProfile.ImprovementLog("goal", "补充项目别名", "关联更容易解释", LocalDate.of(2026, 9, 28)));
        String[] titles = {"SelfAnalyst — 本体关系与证据设计", "SelfAnalyst PR — 项目归属纠错", "Java SQLite 事务文档", "阅读项目架构说明", "SelfAnalyst — 桌面知识界面"};
        for (int i = 0; i < titles.length; i++) {
            Instant time = Instant.parse("2026-09-28T02:00:00Z").minusSeconds(i * 86400L);
            String app = i % 2 == 0 ? "IntelliJ IDEA" : "Microsoft Edge";
            wiki.upsert(new WikiEntry("review-" + i, WikiLevel.HOUR, time, time.plusSeconds(3600), "Asia/Shanghai", WikiStatus.SUMMARIZED,
                    titles[i], titles[i], List.of(new WikiEntry.TaskSegment(titles[i], "涉及项目开发与相关资料查看", List.of(), List.of(app), "medium", List.of("f1"), "inferred")),
                    new WikiEntry.WikiMetrics(1200, 0, 0, List.of(), Map.of("evidenceFacts", List.of(Map.of("id", "f1", "app", app, "title", titles[i])))),
                    List.of(), "fixture", "fixture", 0, null, null, time, time, time));
        }
        OntologyService ontology = new OntologyService(new OntologyStore(root.resolve("ontology.db")), new OntologySources(wiki, () -> profile, WikiPrivacyPolicy.none()));
        ontology.saveEntity(null, "project", "SelfAnalyst", "0.6 个人知识与本体功能 · 合成验收数据", List.of("self-analyst"));
        ontology.saveEntity(null, "topic", "Java", "开发语言与运行环境", List.of());
        ontology.saveEntity(null, "topic", "本体", "实体、关系与来源证据", List.of("知识界面"));
        EventServer server = new EventServer(root.resolve("events"), 0, "ontology-review");
        new DesktopOntologyController(ontology).register(server.app());
        server.app().get("/review", ctx -> { ctx.cookie("self_analyst_session", "ontology-review"); ctx.redirect("/desktop-ui/index.html"); });
        server.app().get("/desktop-ui/init.js", ctx -> ctx.contentType("application/javascript").result(initializationFunctions() + """
                cacheDom(); state.lang='zh'; state.dateLocale='zh-CN';
                loadI18n('zh').then(function(){ applyI18n(document); document.getElementById('error-overlay').classList.add('hidden');
                setupEvents(); switchTab('knowledge'); });
                """));
        server.app().get("/desktop-ui/locales/{file}", ctx -> serve(ctx, "locales/" + ctx.pathParam("file")));
        server.app().get("/desktop-ui/{file}", ctx -> serve(ctx, ctx.pathParam("file")));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.stop(); ontology.close(); wiki.close(); }));
        server.start(); System.out.println("ONTOLOGY_REVIEW_URL=http://localhost:" + server.port() + "/review");
        new java.util.concurrent.CountDownLatch(1).await();
    }
    private static String initializationFunctions() throws Exception {
        try (var input = OntologyReviewServer.class.getResourceAsStream("/desktop-ui/init.js")) {
            String source = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return source.substring(0, source.indexOf("// Start when DOM is ready"));
        }
    }
    private static void serve(io.javalin.http.Context ctx, String name) throws Exception {
        if (name.contains("..")) { ctx.status(404); return; }
        try (var input = OntologyReviewServer.class.getResourceAsStream("/desktop-ui/" + name)) {
            if (input == null) { ctx.status(404); return; }
            String type = name.endsWith(".js") ? "application/javascript" : name.endsWith(".css") ? "text/css"
                    : name.endsWith(".json") ? "application/json" : name.endsWith(".svg") ? "image/svg+xml" : name.endsWith(".png") ? "image/png" : "text/html";
            ctx.contentType(type).result(input.readAllBytes());
        }
    }
}
