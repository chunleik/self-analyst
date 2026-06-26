package com.selfanalyst.wiki.usage;

/**
 * 接收单次 LLM / embedding 调用的 token 用量。由 app 模块的 UsageMeter 实现并注入到
 * 下层模块（如 embedding 客户端），从而把 token 计量收口到一处，而无需下层模块依赖 app。
 *
 * <p>放在 wiki 模块（app 与 file 的共同下层）以便跨模块共享。
 */
@FunctionalInterface
public interface UsageRecorder {

    /**
     * 记录一次调用消耗的 token。
     *
     * @param inputTokens  输入/prompt token（embedding 仅有此值）
     * @param outputTokens 输出/生成 token（embedding 传 0）
     */
    void recordTokens(long inputTokens, long outputTokens);

    /** 不做任何记录的实现，作为可选依赖的默认值。 */
    UsageRecorder NOOP = (inputTokens, outputTokens) -> { };
}
