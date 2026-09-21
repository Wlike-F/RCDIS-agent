package com.rcdis.agent.agent;

import java.util.List;

import org.springframework.stereotype.Component;

import com.rcdis.agent.agent.tools.AuditTools;
import com.rcdis.agent.agent.tools.BasicTools;
import com.rcdis.agent.agent.tools.MemoryTools;
import com.rcdis.agent.agent.tools.ProjectBudgetTools;
import com.rcdis.agent.agent.tools.PlanTools;
import com.rcdis.agent.agent.tools.ReceiptTools;
import com.rcdis.agent.agent.tools.ReimbursementTools;

import lombok.RequiredArgsConstructor;

/**
 * Central registry of Agent tool beans.
 *
 * <p>Keeping the list here means {@code AgentApplicationServiceImpl} wires tools onto the
 * ChatClient without knowing every domain class, and adding a tool later is a one-line change in
 * this constructor plus the new {@code @Tool} class.</p>
 */
@Component
@RequiredArgsConstructor
public class AgentToolRegistry {

    private final ProjectBudgetTools projectBudgetTools;
    private final ReimbursementTools reimbursementTools;
    private final AuditTools auditTools;
    private final BasicTools basicTools;
    private final MemoryTools memoryTools;
    private final PlanTools planTools;
    private final ReceiptTools receiptTools;

    /** The {@code @Tool}-annotated beans to register on each ChatClient request. */
    public List<Object> toolBeans() {
        return List.of(projectBudgetTools, reimbursementTools, auditTools, basicTools,
                memoryTools, planTools, receiptTools);
    }
}
