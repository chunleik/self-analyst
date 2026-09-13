package com.selfanalyst.document;

import java.nio.file.*;
import java.lang.management.ManagementFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DocumentBenchmarkTest {
    @TempDir Path temp;
    @Test
    @EnabledIfSystemProperty(named = "document.benchmark", matches = "true")
    void maximumRowSpoolAndStreamingRenderStayWithinBudget() throws Exception {
        long start = System.nanoTime();
        ManagementFactory.getMemoryPoolMXBeans().forEach(pool -> pool.resetPeakUsage());
        try (var spool = new DocumentRowSpool(temp.resolve("rows.part"))) {
            var factory = DocumentRequest.JSON.getNodeFactory();
            for (int i = 0; i < DocumentRequest.MAX_ROWS; i++) spool.append(List.of(factory.numberNode(i), factory.textNode("中文记录" + i)));
            assertThrows(IllegalArgumentException.class, () -> spool.append(List.of(factory.numberNode(100001))));
            var source = DocumentRequest.JSON.createObjectNode(); source.put("schemaVersion", 1); source.putObject("exportQuery").put("source", "benchmark");
            for (var format : List.of(DocumentFormat.CSV, DocumentFormat.JSON, DocumentFormat.XLSX)) {
                var request = new DocumentRequest(format, "容量测试", source, List.of(), List.of(new DocumentRequest.Sheet("明细", List.of("序号", "标题"), spool)));
                var budget = new DocumentBudget(() -> false);
                Path file = temp.resolve("rows." + format.extension);
                new DocumentRenderer().render(request, file, budget); DocumentFormatVerifier.verify(format, file, budget);
                assertTrue(Files.size(file) < DocumentBudget.MAX_FILE_BYTES);
            }
        }
        assertFalse(Files.exists(temp.resolve("rows.part")));
        long peak = ManagementFactory.getMemoryPoolMXBeans().stream().filter(pool -> pool.getType() == java.lang.management.MemoryType.HEAP)
                .mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
        Files.createDirectories(Path.of("target/document-samples"));
        Files.writeString(Path.of("target/document-samples/benchmark.json"), "{\"rows\":100000,\"elapsedMs\":"
                + (System.nanoTime() - start) / 1_000_000 + ",\"heapPoolPeakSumBytes\":" + peak + "}");
    }
}
