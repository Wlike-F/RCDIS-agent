package com.rcdis.agent.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rcdis.agent.common.response.ApiResponse;
import com.rcdis.agent.dto.AuthLoginRequest;
import com.rcdis.agent.dto.AuthLoginResponse;
import com.rcdis.agent.service.AuthService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Auth")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Issue JWT access token")
    @PostMapping("/login")
    public ApiResponse<AuthLoginResponse> login(@Valid @RequestBody AuthLoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }
}
