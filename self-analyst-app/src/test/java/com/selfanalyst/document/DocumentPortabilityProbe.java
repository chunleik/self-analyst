package com.selfanalyst.document;

import java.nio.file.Files;
import java.nio.file.Path;

/** 用发行 JRE 和 shaded JAR 执行的七格式冒烟入口，不依赖测试框架。 */
public final class DocumentPortabilityProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("必须指定验收输出目录");
        Path root = Path.of(args[0]).toAbsolutePath().normalize(); Files.createDirectories(root);
        var results = DocumentRequest.JSON.createObjectNode();
        for (var format : DocumentFormat.values()) {
            var source = DocumentRequest.JSON.createObjectNode(); source.put("schemaVersion", 1);
            var sheet = source.putArray("sheets").addObject(); sheet.put("name", "验收数据");
            sheet.putArray("columns").add("项目").add("状态"); sheet.putArray("rows").addArray().add("中文文档生成").add("通过");
            if (format != DocumentFormat.CSV && format != DocumentFormat.XLSX)
                source.putArray("blocks").addObject().put("type", "paragraph").put("text", "由发行包内的 Java 运行时生成，不调用 Office、Python 或 Node。");
            Path target = root.resolve("portable." + format.extension);
            var budget = new DocumentBudget(() -> false);
            new DocumentRenderer().render(DocumentRequest.parse(format.name(), "发行环境验收", source.toString()), target, budget);
            DocumentFormatVerifier.verify(format, target, budget);
            results.put(format.name(), Files.size(target));
        }
        Files.writeString(root.resolve("results.json"), results.toPrettyString());
        System.out.println("七种格式全部生成并通过格式检查");
    }
}
