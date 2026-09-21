package com.rcdis.agent.service;

import com.rcdis.agent.vo.AgentTaskVO;
import com.rcdis.agent.common.context.CurrentUserTO;

/** Bounded Plan-and-Execute workflow for submitting material-complete draft reimbursements. */
public interface AgentTaskService {

    AgentTaskVO planReimbursementSubmissions(
            String conversationId, String projectCode, String reason, CurrentUserTO actor);

    AgentTaskVO execute(Long taskId);

    AgentTaskVO prepareRetry(Long taskId);

    AgentTaskVO get(Long taskId);
}
