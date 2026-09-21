package com.rcdis.agent.eval;

/**
 * Root-cause bucket for one deterministic assertion failure. Labels are Chinese because the
 * tuning report is read by the human maintainer of the system prompt.
 */
public enum AgentEvalFailureType {

    TOOL_OMITTED("漏调必调工具"),
    TOOL_FORBIDDEN_INVOKED("误调禁调工具"),
    CONFIRMATION_GATE("确认门违规"),
    FACT_INCONSISTENCY("事实不一致或幻觉"),
    RUNTIME_ERROR("运行时错误"),
    UNKNOWN("未分类");

    private final String label;

    AgentEvalFailureType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
