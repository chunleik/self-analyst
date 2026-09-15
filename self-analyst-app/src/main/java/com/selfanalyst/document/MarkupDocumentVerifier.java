package com.selfanalyst.document;

import java.io.StringReader;
import java.util.Locale;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

/** 静态格式检查；绝不启动浏览器、执行脚本或解析外部实体。 */
final class MarkupDocumentVerifier {
    static final String CSP = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
            + "img-src data:; font-src 'none'; connect-src 'none'; media-src 'none'; "
            + "object-src 'none'; frame-src 'none'; worker-src 'none'; base-uri 'none'; form-action 'none'";
    static final String HTML_POLICY = "<meta charset=\"utf-8\"><meta http-equiv=\"Content-Security-Policy\" content=\"" + CSP + "\">";
    private static final Set<String> BLOCKED_HTML = Set.of("base", "iframe", "frame", "frameset", "object", "embed", "applet", "portal");
    private static final Set<String> URI_ATTRIBUTES = Set.of("href", "xlink:href", "src", "action", "formaction", "poster", "background", "cite", "longdesc", "data");
    private static final Set<String> SVG_ELEMENTS = Set.of("svg", "g", "defs", "title", "desc", "metadata", "path", "rect", "circle",
            "ellipse", "line", "polyline", "polygon", "text", "tspan", "textpath", "use", "symbol", "view", "marker",
            "lineargradient", "radialgradient", "stop", "pattern", "clippath", "mask", "filter", "feblend",
            "fecolormatrix", "fecomponenttransfer", "fecomposite", "feconvolvematrix", "fediffuselighting", "fedisplacementmap",
            "fedistantlight", "fedropshadow", "feflood", "fefunca", "fefuncb", "fefuncg", "fefuncr", "fegaussianblur",
            "femerge", "femergenode", "femorphology", "feoffset", "fepointlight", "fespecularlighting", "fespotlight",
            "fetile", "feturbulence", "style");
    private static final String SVG_NS = "http://www.w3.org/2000/svg";
    private static final int MAX_DEPTH = 128;

    private MarkupDocumentVerifier() {}

    static String prepareHtml(String content, DocumentBudget budget) {
        Document document = html(content, budget, false);
        int headEnd = document.head().sourceRange().end().pos();
        // 只在显式 head 开始处插入策略，保留脚本、CSS、SVG 的原始字节语义。
        return content.substring(0, headEnd) + HTML_POLICY + content.substring(headEnd);
    }

    static Document html(String content, DocumentBudget budget, boolean published) {
        budget.check();
        var parser = Parser.htmlParser().setTrackPosition(true).setTrackErrors(20);
        Document document = parser.parseInput(content, "");
        for (Element element : new Element[]{document.selectFirst("html"), document.head(), document.body()}) {
            if (element == null || !element.sourceRange().isTracked() || element.sourceRange().isImplicit()
                    || !element.endSourceRange().isTracked() || element.endSourceRange().isImplicit())
                throw new IllegalArgumentException("HTML 需要完整且显式闭合的 html/head/body");
        }
        if (!parser.getErrors().isEmpty()) throw new IllegalArgumentException("HTML 结构无效，请检查重复属性、标签嵌套和文档声明");
        int policies = 0;
        for (Element element : document.getAllElements()) {
            budget.check();
            String tag = element.normalName();
            if (BLOCKED_HTML.contains(tag)) throw new IllegalArgumentException("HTML 不支持嵌入外部页面或对象");
            if (element.parents().size() > MAX_DEPTH) throw new IllegalArgumentException("文档嵌套超过 128 层");
            if (tag.equals("meta")) {
                if (element.hasAttr("charset") && !element.attr("charset").equalsIgnoreCase("utf-8"))
                    throw new IllegalArgumentException("HTML 必须使用 UTF-8");
                if (element.hasAttr("http-equiv")) {
                    if (!published || !element.attr("http-equiv").equalsIgnoreCase("Content-Security-Policy")
                            || !element.attr("content").equals(CSP) || element.parent() != document.head())
                        throw new IllegalArgumentException("HTML 不支持自定义 http-equiv，请由系统设置内容策略");
                    policies++;
                }
            }
            if (tag.equals("style")) MarkupResources.css(element.data(), true, budget);
            if (tag.equals("script") && element.hasAttr("src"))
                throw new IllegalArgumentException("HTML 脚本必须内嵌，不能使用 src");
            for (var attribute : element.attributes()) {
                String key = attribute.getKey().toLowerCase(Locale.ROOT);
                String value = attribute.getValue();
                if (key.equals("srcdoc") || key.equals("srcset") || key.equals("imagesrcset") || key.equals("ping") || key.equals("manifest"))
                    throw new IllegalArgumentException("HTML 不支持该外部资源属性：" + key);
                if (URI_ATTRIBUTES.contains(key)) MarkupResources.reference(value, key.equals("src") && tag.equals("img"));
                if (key.equals("style") || Set.of("fill", "stroke", "filter", "clip-path", "mask", "marker-start", "marker-mid", "marker-end", "cursor").contains(key))
                    MarkupResources.css(value, true, budget);
            }
        }
        if (published && (policies != 1 || !content.substring(document.head().sourceRange().end().pos()).startsWith(HTML_POLICY)))
            throw new IllegalArgumentException("HTML 缺少受控内容策略");
        budget.check();
        return document;
    }

    static void svg(String content, DocumentBudget budget) {
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setXMLResolver((publicId, systemId, base, namespace) -> { throw new XMLStreamException("不允许外部实体"); });
        int depth = 0;
        boolean root = false, style = false;
        StringBuilder css = new StringBuilder();
        try {
            var reader = factory.createXMLStreamReader(new StringReader(content));
            try {
                String encoding = reader.getCharacterEncodingScheme();
                if (encoding != null && !encoding.equalsIgnoreCase("UTF-8"))
                    throw new IllegalArgumentException("SVG 编码声明必须是 UTF-8");
                while (reader.hasNext()) {
                    budget.check();
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE
                            || event == XMLStreamConstants.PROCESSING_INSTRUCTION)
                        throw new IllegalArgumentException("SVG 不支持 DTD、实体或处理指令");
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        if (++depth > MAX_DEPTH) throw new IllegalArgumentException("SVG 嵌套超过 128 层");
                        String tag = reader.getLocalName().toLowerCase(Locale.ROOT);
                        if (!SVG_NS.equals(reader.getNamespaceURI()) || !SVG_ELEMENTS.contains(tag))
                            throw new IllegalArgumentException("SVG 含不支持的元素或命名空间：" + tag);
                        if (!root) {
                            if (!reader.getLocalName().equals("svg")) throw new IllegalArgumentException("SVG 根元素必须是 svg");
                            viewport(reader.getAttributeValue(null, "viewBox"), reader.getAttributeValue(null, "width"), reader.getAttributeValue(null, "height"));
                            root = true;
                        }
                        for (int i = 0; i < reader.getAttributeCount(); i++) {
                            String name = reader.getAttributeLocalName(i).toLowerCase(Locale.ROOT);
                            String value = reader.getAttributeValue(i);
                            String namespace = reader.getAttributeNamespace(i);
                            if (name.startsWith("on") || name.equals("base") || name.equals("src")
                                    || namespace != null && !namespace.isEmpty() && !namespace.equals("http://www.w3.org/1999/xlink")
                                        && !namespace.equals("http://www.w3.org/XML/1998/namespace"))
                                throw new IllegalArgumentException("SVG 不支持事件或外部属性：" + name);
                            if (name.equals("href")) MarkupResources.reference(value, false);
                            if (name.equals("style") || Set.of("fill", "stroke", "filter", "clip-path", "mask", "marker-start", "marker-mid", "marker-end", "cursor").contains(name))
                                MarkupResources.css(value, false, budget);
                        }
                        if (tag.equals("style")) { style = true; css.setLength(0); }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if (reader.getLocalName().equals("style")) { MarkupResources.css(css.toString(), false, budget); style = false; }
                        depth--;
                    } else if (style && (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)) css.append(reader.getText());
                }
            } finally { reader.close(); }
        } catch (XMLStreamException failure) {
            throw new IllegalArgumentException("SVG 不是完整的安全 XML", failure);
        }
        if (!root || depth != 0) throw new IllegalArgumentException("SVG 内容不完整");
        budget.check();
    }

    private static void viewport(String viewBox, String width, String height) {
        if (viewBox != null) {
            String[] values = viewBox.strip().split("[\\s,]+");
            try {
                if (values.length != 4) throw new NumberFormatException();
                for (int i = 0; i < 4; i++) {
                    double number = Double.parseDouble(values[i]);
                    if (!Double.isFinite(number) || i >= 2 && number <= 0) throw new NumberFormatException();
                }
                return;
            } catch (NumberFormatException failure) { throw new IllegalArgumentException("SVG viewBox 必须包含四个有限数字且宽高为正"); }
        }
        if (!dimension(width) || !dimension(height)) throw new IllegalArgumentException("SVG 需要有效 viewBox 或正数 width/height");
    }

    private static boolean dimension(String value) {
        if (value == null || !value.matches("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:px|pt|pc|mm|cm|in|em|ex)?")) return false;
        double number = Double.parseDouble(value.replaceFirst("[a-z]+$", ""));
        return number > 0 && Double.isFinite(number);
    }
}
