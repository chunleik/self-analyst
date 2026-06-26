package com.selfanalyst.wiki.usage;

/**
 * 当 LLM 用量已达到每日预算且预算模式为 block 时抛出。
 *
 * <p>放在 wiki 模块以便 app（抛出方）与 wiki/file 的后台 worker（捕获方）都能引用：
 * 后台 worker 捕获后应「保持 PENDING、下个周期重试」，而非标记为永久失败。
 */
public class BudgetExceededException extends RuntimeException {

    public BudgetExceededException(String message) {
        super(message);
    }
}
