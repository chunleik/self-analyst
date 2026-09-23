package com.selfanalyst.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WikiQualityEvaluationTest {
    @Test void offlineDefaultDeclaresFormalConfigurationWithoutCallingModel(@TempDir Path root) throws Exception {
        Path report = root.resolve("report.json");
        WikiQualityEvaluation.main(new String[]{"--case", "cross-application-project", "--repeat", "1",
                "--output", report.toString()});
        var output = new ObjectMapper().readTree(Files.readString(report));
        assertEquals("offline-synthetic", output.path("mode").asText());
        assertEquals("formal", output.path("transport").asText());
        assertEquals(1, output.path("sdkMaxAttempts").asInt());
        assertEquals(0, output.path("totalCalls").asInt());
        assertTrue(output.path("maxOutputTokens").isNull());
        assertTrue(output.path("responseFormat").isNull());
        assertEquals(WikiTopicProtocol.VERSION, output.path("topicProtocolVersion").asText());
        assertEquals("offline-no-model", output.path("runs").get(0).path("status").asText());
    }

    @Test void providerOutputLimitRequiresExplicitDiagnosticMode() throws Exception {
        Class<?> options = Class.forName(WikiQualityEvaluation.class.getName() + "$Options");
        var parse = options.getDeclaredMethod("parse", String[].class);
        parse.setAccessible(true);
        var rejected = assertThrows(InvocationTargetException.class,
                () -> parse.invoke(null, (Object) new String[]{"--max-output-tokens", "4096"}));
        assertInstanceOf(IllegalArgumentException.class, rejected.getCause());
        assertNotNull(parse.invoke(null, (Object) new String[]{"--transport", "diagnostic-http", "--max-output-tokens", "4096"}));
    }

    @Test @SuppressWarnings("unchecked") void topicCandidateDiagnosticsStayWhitelistedAndBounded() throws Exception {
        var extract = WikiQualityEvaluation.class.getDeclaredMethod("candidateForReview", String.class);
        extract.setAccessible(true);
        var candidate = (Map<String, Object>) extract.invoke(null, """
                {"summary":"观察到测试主题","primaryTask":"测试","apiKey":"forbidden-secret","topicCards":[{
                  "title":"测试主题","summary":"查看测试资料","memberInputIds":["f-1","f-2"],
                  "representativeFactIds":["f-1"],"sourceTopicIds":["t-1"],"secret":"forbidden-secret",
                  "claimType":"inferred","confidence":"medium"}]}
                """);
        String encoded = new ObjectMapper().writeValueAsString(candidate);
        assertTrue(encoded.contains("memberInputIds"));
        assertTrue(encoded.contains("candidateCounts"));
        assertFalse(encoded.contains("forbidden-secret"));
        assertEquals("unvalidated", candidate.get("validationStatus"));
    }
}
