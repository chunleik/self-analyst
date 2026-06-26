package com.selfanalyst.content.uia;

/**
 * UIAutomation tree walker backed by the long-lived accessibility sidecar
 * (SPEC-AXS-*). Delegates the native walk to the shared sidecar process and
 * keeps the text-extraction rules (SPEC-UIA-005/006) on the resulting tree.
 */
public class UiaTreeWalker {

    public record UiaWalkResult(String text, UiaNode root) {
        public UiaWalkResult { if (text == null) text = ""; }
    }

    private final AxSidecarClient client = AxSidecarClient.shared();

    /**
     * Walk the accessibility tree for a neutral window handle.
     *
     * @param handle neutral window handle (Windows: HWND numeric value); 0 = none
     */
    public UiaWalkResult walk(long handle) {
        if (handle == 0) return new UiaWalkResult("", null);
        try {
            UiaNode root = client.query(handle);
            if (root == null) return new UiaWalkResult("", null);
            StringBuilder text = new StringBuilder();
            extractText(root, text);
            String txt = text.toString().trim();
            return new UiaWalkResult(txt, root);
        } catch (Exception e) {
            return new UiaWalkResult("", null);
        }
    }

    // ── Text extraction ───────────────────────────────────────────

    static void extractText(UiaNode node, StringBuilder buf) {
        if (node == null) return;
        int type = node.controlType();
        if (type == 50014 || type == 50006 || type == 50038
                || type == 50027 || type == 50022 || type == 50012) return;
        if (node.isPassword()) { append(buf, "***"); return; }
        if (type == 50004 || type == 50003) {
            append(buf, nonEmpty(node.value(), node.name()));
            return;
        }
        if (type == 50030 || type == 50026 || type == 50033
                || type == 50032 || type == 50025 || type == 50000) {
            append(buf, nonEmpty(node.name(), node.value()));
            for (UiaNode child : node.children()) extractText(child, buf);
            return;
        }
        if (type == 50020 || type == 50007 || type == 50005 || type == 50019
                || type == 50011 || type == 50024 || type == 50029 || type == 50034
                || type == 50035 || type == 50037 || type == 50002 || type == 50013) {
            append(buf, node.name());
            return;
        }
        for (UiaNode child : node.children()) extractText(child, buf);
    }

    private static void append(StringBuilder buf, String s) {
        if (s != null && !s.isBlank()) {
            if (!buf.isEmpty()) buf.append('\n');
            buf.append(s.trim());
        }
    }

    private static String nonEmpty(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v.trim();
        return null;
    }

    public static String controlTypeName(int id) {
        return switch (id) {
            case 50000 -> "Button"; case 50002 -> "CheckBox"; case 50003 -> "ComboBox";
            case 50004 -> "Edit"; case 50005 -> "Hyperlink"; case 50006 -> "Image";
            case 50007 -> "ListItem"; case 50010 -> "MenuBar"; case 50011 -> "MenuItem";
            case 50012 -> "ProgressBar"; case 50013 -> "RadioButton"; case 50014 -> "ScrollBar";
            case 50015 -> "Slider"; case 50018 -> "Tab"; case 50019 -> "TabItem";
            case 50020 -> "Text"; case 50021 -> "ToolBar"; case 50022 -> "ToolTip";
            case 50023 -> "Tree"; case 50024 -> "TreeItem"; case 50025 -> "Custom";
            case 50026 -> "Group"; case 50027 -> "Thumb"; case 50028 -> "DataGrid";
            case 50029 -> "DataItem"; case 50030 -> "Document"; case 50032 -> "Window";
            case 50033 -> "Pane"; case 50034 -> "Header"; case 50035 -> "HeaderItem";
            case 50037 -> "TitleBar"; case 50038 -> "Separator";
            default -> "Unknown(" + id + ")";
        };
    }
}
