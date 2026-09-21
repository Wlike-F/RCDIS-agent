package com.rcdis.agent.agent;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.rcdis.agent.service.AgentPendingActionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Recovers approved Agent commands whose worker lease expired after a process failure. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentExecutionRecoveryScheduler {

    private final AgentPendingActionService pendingActionService;
    private final AgentMetrics agentMetrics;

    @Scheduled(fixedDelayString = "${rcdis.agent.recovery-delay-ms:60000}")
    public void recoverExpiredExecutions() {
        int recovered = pendingActionService.recoverExpiredExecutions();
        agentMetrics.recordRecovery(recovered > 0 ? "recovered" : "none");
        if (recovered > 0) {
            log.atInfo().addKeyValue("recoveredCount", recovered)
                    .log("Recovered expired Agent command leases");
        }
    }
}
