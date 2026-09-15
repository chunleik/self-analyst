package com.selfanalyst.document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MarkupDocumentTest {
    @TempDir Path temp;
    static final String SVG = """
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 640 240">
          <title>中文矢量图</title><defs>
            <linearGradient id="gradient"><stop offset="0" stop-color="#2563eb"/><stop offset="1" stop-color="#22c55e"/></linearGradient>
            <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto"><path d="M 0 0 L 10 5 L 0 10 z"/></marker>
            <rect id="box" width="160" height="80" rx="12" fill="url(#gradient)"/>
          </defs><use href="#box" x="20" y="70"/><use href="#box" x="400" y="70"/>
          <path d="M 190 110 L 390 110" stroke="#123456" marker-end="url(#arrow)"/>
          <text x="45" y="115" fill="white">输入数据</text><text x="425" y="115" fill="white">输出结果</text>
        </svg>
        """;
    static final String HTML = """
        <!DOCTYPE html><html lang="zh-CN"><head><title>离线交互报告</title>
        <style>body{font-family:system-ui;margin:40px;background:#f5f7fb;color:#16233c}button,input{padding:8px;margin:8px}svg{display:block;border:1px solid #ddd}tr[hidden]{display:none}</style>
        </head><body><h1>离线交互报告</h1>
        <label>数值 <input id="value" type="number" value="3"></label><button id="calculate">计算两倍</button><output id="result">6</output>
        <button id="filter">只看已完成</button><table><tbody><tr><td>已完成</td></tr><tr id="pending"><td>待处理</td></tr></tbody></table>
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 100" width="400"><defs><linearGradient id="color"><stop stop-color="#2563eb"/><stop offset="1" stop-color="#22c55e"/></linearGradient></defs><rect id="bar" x="0" y="10" width="60" height="40" fill="url(#color)"/><text x="0" y="80">计算结果随按钮更新</text></svg>
        <script>
        document.getElementById('calculate').addEventListener('click', () => {
          const result = Number(document.getElementById('value').value) * 2;
          document.getElementById('result').textContent = String(result);
          document.getElementById('bar').setAttribute('width', String(result * 10));
        });
        document.getElementById('filter').addEventListener('click', () => {
          document.getElementById('pending').hidden = !document.getElementById('pending').hidden;
        });
        </script></body></html>
        """;

    static String source(String kind, String content) {
        return DocumentRequest.JSON.createObjectNode().put("schemaVersion", 2).put("kind", kind).put("content", content).toString();
    }
    private Path render(String kind, String content) throws Exception {
        var path = temp.resolve(java.util.UUID.randomUUID() + "." + kind);
        var budget = new DocumentBudget(() -> false);
        new DocumentRenderer().render(DocumentRequest.parse(kind, "测试", source(kind, content)), path, budget);
        DocumentFormatVerifier.verify(DocumentFormat.parse(kind), path, budget);
        return path;
    }

    @Test void explicitSourceValidationAndLegacyCompatibility() {
        assertTrue(DocumentRequest.parse("HTML", "页面", source("html", HTML)).hasMarkupSource());
        for (String invalid : List.of(source("svg", SVG), source("html", " "), source("html", HTML).replace("\"kind\"", "\"unknown\""),
                source("html", "a".repeat(DocumentRequest.MAX_SOURCE_BYTES)), source("html", HTML).replace("2,", "2,\"blocks\":[],")))
            assertThrows(IllegalArgumentException.class, () -> DocumentRequest.parse("html", "测试", invalid));
        for (var format : DocumentFormat.values()) {
            if (format != DocumentFormat.HTML && format != DocumentFormat.SVG)
                assertThrows(IllegalArgumentException.class, () -> DocumentRequest.parse(format.name(), "测试", source("html", HTML)));
        }
        assertThrows(IllegalArgumentException.class, () -> DocumentRequest.parse("svg", "测试", "{\"schemaVersion\":1,\"blocks\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> DocumentRequest.parse("html", "测试", source("html", HTML).replace("\"schemaVersion\":2", "\"schemaVersion\":4294967298")));
    }

    @Test void interactiveHtmlAndSvgPreserveSourceAndCreateSamples() throws Exception {
        Path html = render("html", HTML), svg = render("svg", SVG);
        String result = Files.readString(html);
        assertEquals(HTML, result.replace(MarkupDocumentVerifier.HTML_POLICY, ""));
        assertEquals(SVG, Files.readString(svg));
        assertTrue(result.indexOf("Content-Security-Policy") < result.indexOf("<script>"));
        assertEquals("utf-8", Jsoup.parse(result).selectFirst("meta[charset]").attr("charset"));
        assertEquals(1, Jsoup.parse(result).select("svg rect#bar").size());
        if (Boolean.getBoolean("document.visual.samples")) {
            Path samples = Path.of("target/document-samples"); Files.createDirectories(samples);
            Files.copy(html, samples.resolve("interactive.html"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(svg, samples.resolve("diagram.svg"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Test void htmlNeverExecutesScriptsDuringGeneration() throws Exception {
        String source = HTML.replace("document.getElementById('calculate').addEventListener", "throw new Error('only runs in browser'); document.getElementById('calculate').addEventListener");
        assertTrue(Files.readString(render("html", source)).contains("only runs in browser"));
    }

    @Test void externalAndIncompleteHtmlFailsExplicitly() {
        for (String body : List.of("<script src='https://example.com/a.js'></script>", "<img src='//example.com/a.png'>",
                "<style>@import 'https://example.com/a.css';</style>", "<style>p{background:u\\72l(https://example.com/a.png)}</style>",
                "<style>p{background:image-set('https://example.com/a.png' 1x)}</style>", "<iframe srcdoc='hello'></iframe>",
                "<meta http-equiv='refresh' content='0;url=https://example.com'>", "<img srcset='a.png 1x'>",
                "<svg><use href='other.svg#x'/></svg>", "<style>@im\\70ort 'other.css';</style>")) {
            assertThrows(IllegalArgumentException.class, () -> render("html", HTML.replace("<h1>", body + "<h1>")), body);
        }
        assertThrows(IllegalArgumentException.class, () -> render("html", "<h1>fragment</h1>"));
        assertThrows(IllegalArgumentException.class, () -> render("html", HTML.replace("</html>", "")));
        assertThrows(IllegalArgumentException.class, () -> render("html", HTML.replace("<title>", "<meta charset='gbk'><title>")));
    }

    @Test void svgRejectsActiveContentExternalEntitiesAndInvalidCanvas() {
        for (String child : List.of("<script>alert(1)</script>", "<rect onclick='alert(1)'/>", "<foreignObject/>",
                "<use href='https://example.com/a.svg#x'/>", "<style>rect{fill:url(https://example.com)}</style>",
                "<style>@import 'external.css';</style>", "<set attributeName='href' to='https://example.com'/>",
                "<style>@font-face{font-family:x;src:'https://example.com/font.woff'}</style>",
                "<g xmlns='http://www.w3.org/1999/xhtml'/>", "<g xml:base='https://example.com'/>"))
            assertThrows(IllegalArgumentException.class, () -> render("svg", SVG.replace("</svg>", child + "</svg>")), child);
        for (String invalid : List.of(SVG.replace("</svg>", ""), SVG.replace("http://www.w3.org/2000/svg", "wrong"),
                SVG.replace("0 0 640 240", "0 0 -1 240"), SVG.replace("0 0 640 240", "0 0 NaN 240"),
                "<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///secret'>]>" + SVG.replace("输入数据", "&x;"),
                "<?xml-stylesheet href='https://example.com/a.css'?>" + SVG))
            assertThrows(IllegalArgumentException.class, () -> render("svg", invalid), invalid);
        assertThrows(IllegalArgumentException.class, () -> render("svg", "<?xml version='1.0' encoding='UTF-16'?>" + SVG));
    }

    @Test void reportEscapesAllDataAndPreservesTablesAndMetadata() throws Exception {
        String dangerous = "</script><script>alert(1)</script>&\"中文";
        var source = DocumentRequest.JSON.createObjectNode().put("schemaVersion", 1);
        source.putObject("metadata").put("source", dangerous);
        source.putArray("blocks").addObject().put("type", "paragraph").put("text", dangerous);
        var sheet = source.putArray("sheets").addObject().put("name", "表格");
        sheet.putArray("columns").add(dangerous);
        sheet.putArray("rows").addArray().add(dangerous);
        var target = temp.resolve("report.html");
        new DocumentRenderer().render(DocumentRequest.parse("html", dangerous, source.toString()), target, new DocumentBudget(() -> false));
        DocumentFormatVerifier.verify(DocumentFormat.HTML, target, new DocumentBudget(() -> false));
        var document = Jsoup.parse(Files.readString(target));
        assertEquals(0, document.select("script").size());
        assertEquals(dangerous, document.selectFirst("td").text());
        assertEquals(dangerous, document.title());
        assertTrue(document.selectFirst("pre").text().contains("source"));
    }

    @Test void budgetsRemainEffective() {
        assertThrows(CancellationException.class, () -> MarkupDocumentVerifier.svg(SVG, new DocumentBudget(() -> true)));
        assertThrows(IllegalArgumentException.class, () -> MarkupDocumentVerifier.svg(SVG.replace("</svg>", "<g>".repeat(129) + "</g>".repeat(129) + "</svg>"), new DocumentBudget(() -> false)));
    }
}
