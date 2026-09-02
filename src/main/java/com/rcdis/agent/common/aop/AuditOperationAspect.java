package com.rcdis.agent.common.aop;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.service.AuditLogService;
import com.rcdis.agent.to.AuditLogEntryTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditOperationAspect {

    private static final String SOURCE_API = "API";

    private final AuditLogService auditLogService;

    @Around("@annotation(auditOperation)")
    public Object around(ProceedingJoinPoint joinPoint, AuditOperation auditOperation) throws Throwable {
        long startNanos = System.nanoTime();
        CurrentUserTO currentUser = CurrentUserContextHolder.currentOrAnonymous();
        try {
            Object result = joinPoint.proceed();
            auditLogService.record(AuditLogEntryTO.success(
                    currentUser,
                    auditOperation.action(),
                    auditOperation.targetType(),
                    targetId(joinPoint),
                    beforeSnapshot(joinPoint),
                    result,
                    reason(joinPoint),
                    SOURCE_API));
            log.atInfo()
                    .addKeyValue("action", auditOperation.action())
                    .addKeyValue("targetType", auditOperation.targetType())
                    .addKeyValue("userId", currentUser.userId())
                    .addKeyValue("tenantId", currentUser.tenantId())
                    .addKeyValue("durationMs", elapsedMillis(startNanos))
                    .log("Audit operation completed");
            return result;
        } catch (Throwable throwable) {
            try {
                auditLogService.record(AuditLogEntryTO.failure(
                        currentUser,
                        auditOperation.action(),
                        auditOperation.targetType(),
                        targetId(joinPoint),
                        beforeSnapshot(joinPoint),
                        throwable,
                        reason(joinPoint),
                        SOURCE_API));
            } catch (RuntimeException auditException) {
                throwable.addSuppressed(auditException);
            }
            log.atWarn()
                    .setCause(throwable)
                    .addKeyValue("action", auditOperation.action())
                    .addKeyValue("targetType", auditOperation.targetType())
                    .addKeyValue("userId", currentUser.userId())
                    .addKeyValue("tenantId", currentUser.tenantId())
                    .addKeyValue("durationMs", elapsedMillis(startNanos))
                    .log("Audit operation failed");
            throw throwable;
        }
    }

    private long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private Map<String, Object> beforeSnapshot(ProceedingJoinPoint joinPoint) {
        return Map.of(
                "method", joinPoint.getSignature().toShortString(),
                "arguments", Arrays.asList(joinPoint.getArgs()));
    }

    private String reason(ProceedingJoinPoint joinPoint) {
        return Arrays.stream(joinPoint.getArgs())
                .filter(AuditReasonProvider.class::isInstance)
                .map(AuditReasonProvider.class::cast)
                .map(AuditReasonProvider::auditReason)
                .filter(reason -> reason != null && !reason.isBlank())
                .findFirst()
                .orElse(null);
    }

    private String targetId(ProceedingJoinPoint joinPoint) {
        return Arrays.stream(joinPoint.getArgs())
                .filter(Number.class::isInstance)
                .map(String::valueOf)
                .findFirst()
                .orElse(null);
    }
}
