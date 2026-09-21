package com.rcdis.agent.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.rcdis.agent.common.response.ApiResponse;
import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.dto.UserCreateRequest;
import com.rcdis.agent.dto.UserPasswordRequest;
import com.rcdis.agent.dto.UserRolesRequest;
import com.rcdis.agent.service.UserService;
import com.rcdis.agent.vo.UserVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/**
 * User administration. Every endpoint requires the ADMIN role; the password hash is never returned.
 */
@Tag(name = "Users")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserService userService;

    @Operation(summary = "Page users")
    @GetMapping
    public ApiResponse<PageResponse<UserVO>> pageUsers(
            @RequestParam @Min(1) long current,
            @RequestParam @Min(1) @Max(500) long size,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(userService.pageUsers(current, size, keyword));
    }

    @Operation(summary = "Get user by id")
    @GetMapping("/{id}")
    public ApiResponse<UserVO> getUser(@PathVariable Long id) {
        return ApiResponse.success(userService.getUser(id));
    }

    @Operation(summary = "Create user")
    @PostMapping
    public ApiResponse<UserVO> createUser(@Valid @RequestBody UserCreateRequest request) {
        return ApiResponse.success(userService.createUser(request));
    }

    @Operation(summary = "Reset user password")
    @PutMapping("/{id}/password")
    public ApiResponse<Void> updatePassword(
            @PathVariable Long id,
            @Valid @RequestBody UserPasswordRequest request) {
        userService.updatePassword(id, request);
        return ApiResponse.success(null);
    }

    @Operation(summary = "Toggle user status between ACTIVE and DISABLED")
    @PostMapping("/{id}/status")
    public ApiResponse<UserVO> toggleStatus(@PathVariable Long id) {
        return ApiResponse.success(userService.toggleStatus(id));
    }

    @Operation(summary = "Assign roles to a user")
    @PutMapping("/{id}/roles")
    public ApiResponse<UserVO> assignRoles(
            @PathVariable Long id,
            @Valid @RequestBody UserRolesRequest request) {
        return ApiResponse.success(userService.assignRoles(id, request));
    }
}
