package com.rcdis.agent.agent;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.rcdis.agent.config.AgentProperties;
import com.rcdis.agent.service.EvalCandidateService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Daily pass that turns failing production turns into review candidates.
 *
 * <p>Runs after the trace-retention job. Harvesting only creates candidates; promotion into the
 * evaluation dataset always requires a human review and a recorded reproduction.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentEvalCandidateHarvestScheduler {

    private final AgentProperties agentProperties;
    private final EvalCandidateService evalCandidateService;

    @Scheduled(cron = "0 10 4 * * *")
    public void harvestDaily() {
        if (!agentProperties.getEvalCandidates().isEnabled()) {
            return;
        }
        try {
            evalCandidateService.harvest(agentProperties.getEvalCandidates().getWindowDays());
        } catch (Exception exception) {
            // A failed harvest only delays candidate discovery; it must never affect business flows.
            log.atWarn()
                    .addKeyValue("reason", "eval-candidate-harvest-failed")
                    .addKeyValue("errorType", exception.getClass().getSimpleName())
                    .log("Daily Agent eval candidate harvest skipped");
        }
    }
}
