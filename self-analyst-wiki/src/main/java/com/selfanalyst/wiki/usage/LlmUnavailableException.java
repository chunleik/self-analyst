package com.selfanalyst.wiki.usage;

/** 本地配置尚未提供模型；后台工作应保持可重试而不是累计远端失败。 */
public class LlmUnavailableException extends IllegalStateException {
    public LlmUnavailableException() { super("LLM 未配置，请在配置页面设置 API Key"); }
}
