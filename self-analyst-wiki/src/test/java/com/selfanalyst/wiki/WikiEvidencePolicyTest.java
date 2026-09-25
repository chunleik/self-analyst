package com.selfanalyst.wiki;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WikiEvidencePolicyTest {
    private final List<WikiEvidencePolicy.EvidenceFact> facts = List.of(
            new WikiEvidencePolicy.EvidenceFact("f1", "IDE", "数据库同步设计", false),
            new WikiEvidencePolicy.EvidenceFact("f2", "Chat", "团队会议", true));

    /** Explicit local replay only; normal test runs never read runtime activity or generated reports. */
    @TestFactory
    java.util.stream.Stream<DynamicTest> replayExplicitSyntheticReport() throws Exception {
        String reportPath = System.getProperty("wiki.policy.syntheticReplay");
        if (reportPath == null) return java.util.stream.Stream.empty();
        List<DynamicTest> cases = new ArrayList<>();
        for (String path : reportPath.split(";")) {
            var report = new com.fasterxml.jackson.databind.ObjectMapper().readTree(java.nio.file.Path.of(path).toFile());
            int runIndex = 0;
            for (var run : report.path("runs")) {
                runIndex++;
                if (!"rejected".equals(run.path("status").asText())) continue;
                var candidate = run.path("unvalidatedCandidate");
                Map<String, String> narratives = new LinkedHashMap<>();
                for (String field : List.of("summary", "primaryTask")) {
                    if (candidate.path(field).isTextual()) narratives.put(field, candidate.path(field).asText());
                }
                int segmentIndex = 0;
                for (var segment : candidate.path("taskSegments")) {
                    for (String field : List.of("title", "summary")) {
                        if (segment.path(field).isTextual()) narratives.put("taskSegments[" + segmentIndex + "]." + field,
                                segment.path(field).asText());
                    }
                    segmentIndex++;
                }
                for (var narrative : narratives.entrySet()) {
                    cases.add(DynamicTest.dynamicTest(java.nio.file.Path.of(path).getFileName() + ":rejected-" + runIndex + ":" + narrative.getKey(), () ->
                            assertDoesNotThrow(() -> WikiEvidencePolicy.validateNarrative(narrative.getKey(), narrative.getValue()), narrative.getValue())));
                }
            }
        }
        assertFalse(cases.isEmpty(), "explicit replay must contain rejected synthetic candidates");
        return cases.stream();
    }

    @Test
    void bindsKnownFactsAndBuildsEvidenceInsteadOfTrustingModelEvidence() {
        var segment = segment();
        segment.put("apps", List.of("模型自行编写的应用"));
        segment.put("evidence", List.of("模型自行编写的证据"));
        var result = WikiEvidencePolicy.parseSegments(List.of(segment), facts).getFirst();
        assertEquals(List.of("f1"), result.evidenceFactIds());
        assertEquals(List.of("IDE"), result.apps());
        assertEquals("observed", result.claimType());
        assertEquals("high", result.confidence());
        assertEquals(List.of("观察到标题：「数据库同步设计」"), result.evidence());
        assertFalse(result.evidence().toString().contains("模型自行编写"));
    }

    @Test
    void redundantModelFieldsCanBeOmittedOrMalformedWithoutAffectingDerivedOutput() {
        var omitted = WikiEvidencePolicy.parseSegments(List.of(segment()), facts).getFirst();
        assertEquals(List.of("IDE"), omitted.apps());
        assertEquals(List.of("观察到标题：「数据库同步设计」"), omitted.evidence());
        for (String field : List.of("apps", "evidence")) {
            for (Object ignored : java.util.Arrays.asList(null, "自由文本", 42, Map.of(), List.of(), List.of(42),
                    List.of("x".repeat(241)), List.of("已完成迁移，AFK覆盖为partial"),
                    java.util.Collections.nCopies(17, "重复值"))) {
                var supplied = segment();
                supplied.put(field, ignored);
                assertEquals(omitted, WikiEvidencePolicy.parseSegments(List.of(supplied), facts).getFirst(), field);
            }
        }
    }

    @Test
    void crossApplicationTasksDeriveEveryCitedAppWithoutModelAliases() {
        var installerFacts = List.of(
                new WikiEvidencePolicy.EvidenceFact("setup", "FlowTool Setup.exe", "FlowTool 安装", false),
                new WikiEvidencePolicy.EvidenceFact("main", "FlowTool.exe", "FlowTool 配置说明", false),
                new WikiEvidencePolicy.EvidenceFact("design", "FlowTool.exe", "FlowTool 设计", false));
        var install = segment();
        install.put("evidenceFactIds", List.of("main", "setup", "design"));
        install.put("apps", List.of("FlowTool"));
        var result = WikiEvidencePolicy.parseSegments(List.of(install), installerFacts).getFirst();
        assertEquals(List.of("FlowTool.exe", "FlowTool Setup.exe"), result.apps());
        assertEquals(List.of("观察到标题：「FlowTool 配置说明」", "观察到标题：「FlowTool 安装」",
                "观察到标题：「FlowTool 设计」"), result.evidence());

        var collaborationFacts = List.of(
                new WikiEvidencePolicy.EvidenceFact("chat", "WXWork.exe", "项目评审讨论", true),
                new WikiEvidencePolicy.EvidenceFact("mail", "OUTLOOK.EXE", "项目评审邮件", false));
        var collaboration = segment();
        collaboration.put("evidenceFactIds", List.of("chat", "mail"));
        collaboration.put("apps", List.of("企业微信"));
        var shared = WikiEvidencePolicy.parseSegments(List.of(collaboration), collaborationFacts).getFirst();
        assertEquals(List.of("WXWork.exe", "OUTLOOK.EXE"), shared.apps());
        assertEquals(List.of("chat", "mail"), shared.evidenceFactIds());
        assertEquals(List.of("观察到标题：「项目评审讨论」", "观察到标题：「项目评审邮件」"), shared.evidence());
    }

    @Test
    void derivingRedundantFieldsDoesNotRelaxReferenceCountOrRequiredFields() {
        var segment = segment();
        var manyFacts = java.util.stream.IntStream.rangeClosed(1, WikiEvidencePolicy.MAX_REFERENCES + 1)
                .mapToObj(index -> new WikiEvidencePolicy.EvidenceFact("f" + index, "IDE", "任务标题", false)).toList();
        segment.put("evidenceFactIds", manyFacts.stream().map(WikiEvidencePolicy.EvidenceFact::id).toList());
        var tooMany = assertThrows(IllegalArgumentException.class,
                () -> WikiEvidencePolicy.parseSegments(List.of(segment), manyFacts));
        assertEquals("WIKI_EVIDENCE_STRUCTURE:taskSegments.evidenceFactIds", tooMany.getMessage());
        segment.put("evidenceFactIds", List.of("f1"));
        for (String required : List.of("title", "summary", "evidenceFactIds", "claimType", "confidence")) {
            var missing = new LinkedHashMap<>(segment);
            missing.remove(required);
            assertThrows(IllegalArgumentException.class,
                    () -> WikiEvidencePolicy.parseSegments(List.of(missing), facts), required);
        }
    }

    @Test
    void confidenceDependsOnCitedEvidenceAndClaimStrength() {
        Map<String, Object> segment = segment();
        assertEquals("high", WikiEvidencePolicy.parseSegments(List.of(segment), facts).getFirst().confidence());
        segment.put("claimType", "inferred");
        assertEquals("medium", WikiEvidencePolicy.parseSegments(List.of(segment), facts).getFirst().confidence());
        segment.put("evidenceFactIds", List.of("f2"));
        segment.put("apps", List.of("Chat"));
        assertEquals("low", WikiEvidencePolicy.parseSegments(List.of(segment), facts).getFirst().confidence());
    }

    @Test
    void rejectsUnknownReferencesAndInvalidClaimsWithoutEchoingContent() {
        for (Map.Entry<String, Object> mutation : List.<Map.Entry<String, Object>>of(
                Map.entry("evidenceFactIds", List.of("PRIVATE_UNKNOWN_ID")),
                Map.entry("claimType", "legacy"), Map.entry("confidence", "certain"))) {
            var segment = segment();
            segment.put(mutation.getKey(), mutation.getValue());
            var error = assertThrows(IllegalArgumentException.class,
                    () -> WikiEvidencePolicy.parseSegments(List.of(segment), facts));
            assertTrue(error.getMessage().startsWith("WIKI_EVIDENCE_"));
            assertFalse(error.getMessage().contains("PRIVATE"));
            assertNull(error.getCause());
        }
    }

    @Test
    void rejectsInvalidShapesEmptyTasksAndUnboundedFields() {
        for (Object raw : List.of(Map.of(), List.of(), List.of("bad"))) {
            assertThrows(IllegalArgumentException.class, () -> WikiEvidencePolicy.parseSegments(raw, facts));
        }
        for (Map.Entry<String, Object> mutation : List.<Map.Entry<String, Object>>of(
                Map.entry("title", 42), Map.entry("summary", ""), Map.entry("title", "x".repeat(81)),
                Map.entry("summary", "x".repeat(501)), Map.entry("confidence", 42), Map.entry("claimType", List.of()),
                Map.entry("evidenceFactIds", "f1"), Map.entry("evidenceFactIds", List.of(42)),
                Map.entry("evidenceFactIds", List.of()), Map.entry("evidenceFactIds", List.of("f1", "f1")))) {
            var segment = segment();
            segment.put(mutation.getKey(), mutation.getValue());
            assertThrows(IllegalArgumentException.class,
                    () -> WikiEvidencePolicy.parseSegments(List.of(segment), facts), mutation.getKey());
        }
        var missing = segment();
        missing.remove("evidenceFactIds");
        assertThrows(IllegalArgumentException.class, () -> WikiEvidencePolicy.parseSegments(List.of(missing), facts));
        var tooMany = new ArrayList<Object>();
        for (int i = 0; i <= WikiEvidencePolicy.MAX_SEGMENTS; i++) tooMany.add(segment());
        assertThrows(IllegalArgumentException.class, () -> WikiEvidencePolicy.parseSegments(tooMany, facts));
    }

    @Test
    void validReferencesNeverProveCompletionOrParticipation() {
        for (String claim : List.of("已完成数据库迁移并成功发布。", "参与团队会议", "发起群聊并添加成员",
                "运行Hop项目", "发送消息", "Successfully deployed the service.")) {
            var segment = segment();
            segment.put("summary", claim);
            assertThrows(IllegalArgumentException.class,
                    () -> WikiEvidencePolicy.parseSegments(List.of(segment), facts), claim);
        }
        for (String observation : List.of("查看已完成订单列表", "查看已发布页面", "阅读「已完成迁移」文档",
                "查看运行项目的配置文档", "查看会议相关页面", "涉及数据库同步相关开发")) {
            assertDoesNotThrow(() -> WikiEvidencePolicy.validateNarrative("summary", observation), observation);
        }
        assertThrows(IllegalArgumentException.class,
                () -> WikiEvidencePolicy.validateNarrative("summary", "查看已完成订单列表，并成功发布服务"));
    }

    @Test
    void allowsLocallyNegatedClaimsAndObservedQuotedTitles() {
        for (String narrative : List.of(
                "无法确认是否参加了会议", "仅看到会议页面，不能认定参与会议",
                "没有证据证明已完成数据库迁移", "尚无证据表明用户已解决数据库问题",
                "不确定是否实际参与会议", "并未参与会议", "不能断言用户已完成迁移",
                "仅看到“参加会议”页面", "页面标题为“已完成迁移”", "观察到标题：「成功发布」",
                "Cannot confirm whether the user attended the meeting.",
                "No evidence that the user has completed the migration.",
                "Read the title \"successfully deployed\".")) {
            assertDoesNotThrow(() -> WikiEvidencePolicy.validateNarrative("summary", narrative), narrative);
        }
    }

    @Test
    void actionWordsInExplicitTitleWordingAreObservationsWithALocalScope() {
        for (String narrative : List.of(
                "涉及协作程序中带添加群聊成员字样的窗口标题，仅反映该窗口观察。",
                "出现含添加群聊成员字样的窗口标题。",
                "查看包含发送邮件字样的页面标题。",
                "涉及带有已完成迁移字样的文档标题。",
                "涉及协作程序中带“添加群聊成员”字样的窗口标题，仅反映该窗口观察。",
                "涉及带添加群聊成员字样的窗口标题，不能据此认定添加了群聊成员。")) {
            assertDoesNotThrow(() -> WikiEvidencePolicy.validateNarrative("summary", narrative), narrative);
        }
        for (String narrative : List.of(
                "涉及协作程序中带添加群聊成员字样的窗口标题，随后添加了群聊成员。",
                "涉及带添加群聊成员字样的窗口标题随后添加了群聊成员。",
                "涉及带添加群聊成员字样的窗口标题，已完成数据库迁移。",
                "涉及带有已完成迁移字样的窗口标题，并成功发布服务。",
                "涉及带添加群聊成员字样的窗口标题，但实际添加了群聊成员。",
                "涉及带添加群聊成员字样的窗口标题，不能据此认定添加了群聊成员，但已完成迁移。",
                "涉及带相关提示随后添加了群聊成员字样的窗口标题。")) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> WikiEvidencePolicy.validateNarrative("summary", narrative), narrative);
            assertEquals("WIKI_EVIDENCE_UNSUPPORTED_CLAIM:summary", error.getMessage());
        }
    }

    @Test
    void observedInterfaceEnumerationsTreatActionNamesAsLocalObjectDescriptions() {
        for (String narrative : List.of(
                "涉及协同门户、协作应用，以及添加群聊成员、转发消息详情、发起群聊等界面。仅表明相关应用或界面被查看/涉及，不证明实际发送消息、参会或完成交付。",
                "涉及协作应用中的群聊、联系人、文件传输助手、添加群聊成员、转发消息详情、图片和视频等窗口。",
                "查看群聊、添加群聊成员和发送消息等界面。",
                "浏览联系人、添加群聊成员，以及发起群聊等窗口。",
                "观察到协作应用中的添加群聊成员界面。",
                "看到添加群聊成员窗口、发送邮件界面与发起群聊窗口。",
                "涉及添加群聊成员等界面，不证明实际发送消息。",
                "查看添加群聊成员窗口，随后查看发起群聊界面。")) {
            assertDoesNotThrow(() -> WikiEvidencePolicy.validateNarrative("summary", narrative), narrative);
        }
    }

    @Test
    void anInterfaceSuffixCannotAbsorbIndependentActionsOrDoubleNegation() {
        for (String narrative : List.of(
                "涉及协作应用、添加群聊成员等界面，随后添加了群聊成员。",
                "涉及协作应用、添加群聊成员等界面随后添加了群聊成员。",
                "涉及协作应用、添加群聊成员等界面但实际发送消息。",
                "涉及协作应用、添加群聊成员等窗口并发送消息。",
                "查看添加群聊成员窗口、发送消息。",
                "查看添加群聊成员窗口后发送消息。",
                "查看添加群聊成员窗口且已发送消息。",
                "查看添加群聊成员窗口，已完成数据库迁移。",
                "查看添加群聊成员窗口，随后发起群聊并成功发布服务。",
                "涉及协作应用，发送消息等界面。",
                "涉及协作应用、随后发送消息等界面。",
                "涉及协作应用、但实际发送消息等界面。",
                "涉及协作应用、并发送消息等界面。",
                "涉及协作应用、添加了群聊成员等界面。",
                "涉及协作应用、已发送消息等界面。",
                "涉及协作应用、并非没有发送消息等界面。",
                "涉及协作应用、并不否认发送消息等界面。",
                "涉及协作应用、不能否认发起群聊等界面。",
                "涉及添加群聊成员等界面，并非不证明实际发送消息。",
                "涉及添加群聊成员等界面，不能认定发送消息，但已完成交付。",
                "添加群聊成员、发送消息等界面。")) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> WikiEvidencePolicy.validateNarrative("summary", narrative), narrative);
            assertEquals("WIKI_EVIDENCE_UNSUPPORTED_CLAIM:summary", error.getMessage());
        }
    }

    @Test
    void aLocalNegationOrQuoteCannotHideALaterIndependentAssertion() {
        for (String narrative : List.of(
                "无法确认是否参加了会议，随后已完成数据库迁移",
                "仅看到会议页面，不能认定参与会议，但已解决数据库问题",
                "没有证据证明已完成数据库迁移，实际已完成数据库迁移",
                "无法确认是否参与会议并参加了会议", "没有证据证明已完成迁移并成功发布服务",
                "并非没有证据证明已完成迁移", "不是无法确认已完成迁移", "无法否认已完成迁移",
                "查看“已完成迁移”文档，并成功发布服务", "页面标题为“参加会议”，随后参与会议",
                "确认“已完成迁移”", "Cannot confirm whether the user attended the meeting, but has completed the migration.",
                "No evidence that the user has completed the migration; successfully deployed the service.")) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> WikiEvidencePolicy.validateNarrative("summary", narrative), narrative);
            assertEquals("WIKI_EVIDENCE_UNSUPPORTED_CLAIM:summary", failure.getMessage());
        }
    }

    @Test
    void oneNonAssertivePredicateCanGovernAlternativeOutcomes() {
        for (String narrative : List.of(
                "标题仍只提供编号信息，未体现具体技术主题或功能范围，可视为同一开发工作流中后续任务的窗口活动记录，不能据此认定任务已完成或问题已解决。",
                "不能认定已完成数据库迁移或问题已解决",
                "不能仅凭窗口标题判断任务已完成或问题已解决",
                "仅凭标题无法判断任务已完成或者用户参与会议",
                "不能据此认定用户参与会议或任务已完成或问题已解决")) {
            assertDoesNotThrow(() -> WikiEvidencePolicy.validateNarrative("summary", narrative), narrative);
        }
        for (String narrative : List.of(
                "不能认定已完成A，但实际已完成B", "不能认定任务已完成或问题已解决，并成功发布服务",
                "不能认定任务已完成或实际已解决问题", "不能认定任务已完成，或问题已解决",
                "不能据此认定任务已完成，随后已完成迁移", "不能认定已完成任务并声称已解决问题")) {
            assertThrows(IllegalArgumentException.class,
                    () -> WikiEvidencePolicy.validateNarrative("summary", narrative), narrative);
        }
    }

    @Test
    void capturedSyntheticNarrativesKeepTheirLocalEvidenceQualifications() {
        for (String narrative : List.of(
                "标题线索表明存在相关资料的查阅活动，但不表示已完成安装或交付。",
                "标题线索仅表明查阅过相关文档，不表示已完成权限配置。",
                "整体属于项目文档整理与配置说明类的相关工作，尚无证据表明文档已完成或配置已交付。",
                "出现与客户需求评审会议相关的窗口标题，仅表明该窗口被观察到，不证明实际参加会议或评审结论。",
                "出现客户需求评审会议相关的窗口标题，仅表明该标题被观察到，不证明实际参加会议、发言或形成评审结论。",
                "该线索仅表明存在相关标题的浏览活动，不能说明设计文档已完成或方案已确定。",
                "出现与订单平台接口评审相关的 Teams 窗口标题，属于评审沟通环节的线索；标题本身不证明已参加会议、发起会话或完成评审结论。",
                "标题仅表明相关窗口被打开或查看，不证明实际发送消息、参加会议或形成结论。",
                "窗口标题本身不表明参与讨论、发起会话或发送消息。",
                "标题仅表明窗口存在，不能说明已参加会议、发起讨论或发送消息。",
                "存在一个标题涉及项目讨论的聊天窗口观察，仅能说明相关窗口标题出现，不足以判断是否参与讨论、发起会话或发送消息。",
                "观察到标题为“项目讨论观察”的聊天窗口，涉及项目讨论主题；标题本身不表明是否实际发送消息或参与讨论。",
                "标题仅表明查看或停留在对应开发内容上，不表示任务已完成。")) {
            assertDoesNotThrow(() -> WikiEvidencePolicy.validateNarrative("summary", narrative), narrative);
        }
        for (String assertion : List.of(
                "不表明参与讨论，但实际已完成迁移", "不足以判断是否参与讨论，并发送消息",
                "不表示已完成安装或实际已完成交付", "并非不表明已完成迁移",
                "不证明实际参加会议，但已解决问题", "没有证据证明任务已完成实际已解决问题")) {
            assertThrows(IllegalArgumentException.class,
                    () -> WikiEvidencePolicy.validateNarrative("summary", assertion), assertion);
        }
    }

    @Test
    void negatedEvidenceHasAnExplicitComplementRangeWithoutSubjectWhitelists() {
        for (String narrative : List.of(
                "标题仅表明相关开发活动被观察，不证明具体任务已完成或交付。",
                "标题观察不构成运行、配置或交付已完成的证据。",
                "这不是整个子项目的新需求已经完成的充分证据。",
                "这些窗口不构成较晚开始的数据库迁移任务已完成的直接依据。",
                "单一页面无法作为该应用的新版本已经成功发布的可靠证据。",
                "但各环节的实际推进情况仅由窗口标题体现，不宜视为已完成成果。")) {
            assertDoesNotThrow(() -> WikiEvidencePolicy.validateNarrative("summary", narrative), narrative);
        }
        for (String narrative : List.of(
                "不构成任务已完成的证据，但实际已完成迁移", "并非不构成任务已完成的证据",
                "不是无法作为任务已完成的证据", "不构成任务已完成的证据且已完成数据库迁移",
                "不构成活动记录，已完成数据库迁移", "不构成已完成迁移并成功发布服务的证据",
                "不宜视为已完成成果，但实际上已完成交付", "并非不宜视为已完成成果")) {
            assertThrows(IllegalArgumentException.class,
                    () -> WikiEvidencePolicy.validateNarrative("summary", narrative), narrative);
        }
    }

    @Test
    void sourceTitleStatisticsStayOutOfEvidenceNarrative() {
        var result = WikiEvidencePolicy.parseSegments(List.of(segment()), List.of(
                new WikiEvidencePolicy.EvidenceFact("f1", "IDE", "AFK覆盖为partial", false)));
        assertEquals(List.of("观察到相关应用的标题线索。"), result.getFirst().evidence());
    }

    private Map<String, Object> segment() {
        var result = new LinkedHashMap<String, Object>();
        result.put("title", "数据库同步相关活动");
        result.put("summary", "查看数据库同步设计");
        result.put("confidence", "high");
        result.put("evidenceFactIds", List.of("f1"));
        result.put("claimType", "observed");
        return result;
    }
}
