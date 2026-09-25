package com.selfanalyst.wiki;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes evidence-boundary disclaimers without hiding an unsupported outcome. A contrastive
 * conjunction starts a new part, so "不证明参会但已解决问题" keeps its outcome for validation.
 */
final class WikiDisclaimer {
    private static final Pattern SENTENCE = Pattern.compile("([^。；;\\n]+)([。；;\\n]?)");
    private static final String PART_BOUNDARY = "(?<=[，,])|(?=但是|但|却|然而|不过)";
    private static final Pattern TRIGGER = Pattern.compile(
            "不证明|不表示|不表明|不代表|仅为.{0,12}标题|仅描述查看|标题本身不|仅表明|仅反映");
    private static final Pattern ACTION = Pattern.compile(
            "运行|配置|交付|发送|消息|参会|会议|完成|成果|提交|发布|通话|登录|审批|观察|查看|打开");
    private static final Pattern LEADING_JOIN = Pattern.compile("^(?:但是|但|却|然而|不过|而且|并且)");

    private WikiDisclaimer() {}

    record Result(String text, int removed) {}

    static Result clean(String text) {
        if (text == null || text.isBlank()) return new Result(text == null ? "" : text, 0);
        StringBuilder kept = new StringBuilder();
        int removed = 0;
        Matcher sentence = SENTENCE.matcher(text);
        while (sentence.find()) {
            StringBuilder rest = new StringBuilder();
            int dropped = 0;
            for (String part : sentence.group(1).split(PART_BOUNDARY)) {
                if (TRIGGER.matcher(part).find() && ACTION.matcher(part).find()) dropped++;
                else rest.append(part);
            }
            if (dropped > 0) removed++;
            String value = rest.toString().strip();
            if (dropped > 0) value = LEADING_JOIN.matcher(value).replaceFirst("").replaceAll("[，,\\s]+$", "").strip();
            if (value.isEmpty()) continue;
            String end = sentence.group(2);
            kept.append(value).append(dropped > 0 && (end.isEmpty() || "\n".equals(end)) ? "。" : end);
        }
        String result = kept.toString().strip();
        if (removed > 0 && !result.isEmpty()) result = result.replaceAll("[；;，,\\s]+$", "")
                .replaceAll("([^。！？!?])$", "$1。");
        return new Result(result, removed);
    }
}
