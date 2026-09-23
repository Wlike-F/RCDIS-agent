package com.rcdis.agent.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.response.ApiResponse;
import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.service.InAppNotificationService;
import com.rcdis.agent.service.NotificationStreamRegistry;
import com.rcdis.agent.vo.AppNotificationVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/**
 * In-app notification endpoints. Every authenticated role may read and acknowledge its own
 * notifications; ownership filtering happens in the service layer.
 */
@Tag(name = "Notifications")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final InAppNotificationService inAppNotificationService;
    private final NotificationStreamRegistry notificationStreamRegistry;

    /**
     * Long-lived SSE stream of the current user's notifications. Events: {@code hello} on connect
     * and {@code notification} carrying one AppNotificationVO payload. Delivery is best-effort:
     * the client reconciles through the REST endpoints on (re)connect, so a dropped stream never
     * loses notifications (the rows are already committed before the push).
     */
    @Operation(summary = "Stream my notifications over SSE")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return notificationStreamRegistry.subscribe(CurrentUserContextHolder.currentOrAnonymous().userId());
    }

    @Operation(summary = "List my notifications")
    @GetMapping
    public ApiResponse<PageResponse<AppNotificationVO>> listMyNotifications(
            @RequestParam @Min(1) long current,
            @RequestParam @Min(1) @Max(100) long size
    ) {
        return ApiResponse.success(inAppNotificationService.pageMyNotifications(current, size));
    }

    @Operation(summary = "Count my unread notifications")
    @GetMapping("/unread-count")
    public ApiResponse<Long> countUnread() {
        return ApiResponse.success(inAppNotificationService.countUnread());
    }

    @Operation(summary = "Mark one notification as read")
    @PutMapping("/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable Long id) {
        inAppNotificationService.markRead(id);
        return ApiResponse.success(null);
    }

    @Operation(summary = "Mark all my notifications as read")
    @PutMapping("/read-all")
    public ApiResponse<Void> markAllRead() {
        inAppNotificationService.markAllRead();
        return ApiResponse.success(null);
    }
}
