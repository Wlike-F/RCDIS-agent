package com.rcdis.agent.to;

import com.rcdis.agent.common.context.CurrentUserTO;

public record AuditLogEntryTO(
        String actor,
        String tenantId,
        String action,
        String targetType,
        String targetId,
        Object beforeSnapshot,
        Object afterSnapshot,
        String reason,
        String source,
        String conversationId
) {

    public static AuditLogEntryTO success(
            CurrentUserTO currentUser,
            String action,
            String targetType,
            String targetId,
            Object beforeSnapshot,
            Object afterSnapshot,
            String source
    ) {
        return success(currentUser, action, targetType, targetId, beforeSnapshot, afterSnapshot, null, source);
    }

    public static AuditLogEntryTO success(
            CurrentUserTO currentUser,
            String action,
            String targetType,
            String targetId,
            Object beforeSnapshot,
            Object afterSnapshot,
            String reason,
            String source
    ) {
        return new AuditLogEntryTO(
                currentUser.userId(),
                currentUser.tenantId(),
                action,
                targetType,
                targetId,
                beforeSnapshot,
                afterSnapshot,
                reason,
                source,
                currentUser.conversationId());
    }

    public static AuditLogEntryTO failure(
            CurrentUserTO currentUser,
            String action,
            String targetType,
            String targetId,
            Object beforeSnapshot,
            Throwable throwable,
            String source
    ) {
        return failure(currentUser, action, targetType, targetId, beforeSnapshot, throwable, null, source);
    }

    public static AuditLogEntryTO failure(
            CurrentUserTO currentUser,
            String action,
            String targetType,
            String targetId,
            Object beforeSnapshot,
            Throwable throwable,
            String reason,
            String source
    ) {
        return new AuditLogEntryTO(
                currentUser.userId(),
                currentUser.tenantId(),
                action,
                targetType,
                targetId,
                beforeSnapshot,
                null,
                failureReason(throwable, reason),
                source,
                currentUser.conversationId());
    }

    private static String failureReason(Throwable throwable, String reason) {
        if (reason == null || reason.isBlank()) {
            return throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        }
        return reason + " | " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
    }
}
