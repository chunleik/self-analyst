package com.selfanalyst.wiki;

/** 周期准入耗尽；消息只包含计量，不包含请求或模型响应。 */
public final class WikiPeriodBudgetException extends RuntimeException {
    private final WikiGenerationStore.BudgetSnapshot snapshot;
    private final WikiGenerationStore.BudgetLimits limits;

    public WikiPeriodBudgetException(WikiGenerationStore.BudgetSnapshot snapshot,
                                    WikiGenerationStore.BudgetLimits limits) {
        super("period_budget_exhausted: calls=" + snapshot.calls() + "/" + limits.maxCalls()
                + ", tokens=" + snapshot.tokens() + "/" + limits.maxTokens());
        this.snapshot = snapshot;
        this.limits = limits;
    }

    public String code() { return "period_budget_exhausted"; }
    public WikiGenerationStore.BudgetSnapshot snapshot() { return snapshot; }
    public WikiGenerationStore.BudgetLimits limits() { return limits; }
}
