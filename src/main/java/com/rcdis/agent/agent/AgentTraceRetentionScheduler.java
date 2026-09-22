package com.rcdis.agent.agent;

import java.time.OffsetDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rcdis.agent.config.AgentProperties;
import com.rcdis.agent.entity.AgentTurnTraceEntity;
import com.rcdis.agent.mapper.AgentTurnTraceMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Daily TTL purge for {@code agent_turn_trace}: rows older than the configured retention window
 * are deleted so the observability period metrics operate on a bounded data set. Disable by
 * setting {@code rcdis.agent.observability.trace-retention-days=0}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentTraceRetentionScheduler {

    private final AgentTurnTraceMapper agentTurnTraceMapper;
    private final AgentProperties agentProperties;

    /** Runs at 03:40 every day, before business hours. */
    @Scheduled(cron = "0 40 3 * * *")
    public void purgeExpiredTraces() {
        int retentionDays = agentProperties.getObservability().getTraceRetentionDays();
        if (retentionDays <= 0) {
            return;
        }
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(retentionDays);
        int deleted = agentTurnTraceMapper.delete(new LambdaQueryWrapper<AgentTurnTraceEntity>()
                .lt(AgentTurnTraceEntity::getCreatedAt, cutoff));
        if (deleted > 0) {
            log.atInfo()
                    .addKeyValue("deleted", deleted)
                    .addKeyValue("retentionDays", retentionDays)
                    .log("Purged expired agent turn traces");
        }
    }
}
