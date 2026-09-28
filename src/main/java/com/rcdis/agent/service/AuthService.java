package com.rcdis.agent.service;

import com.rcdis.agent.dto.AuthLoginRequest;
import com.rcdis.agent.dto.AuthLoginResponse;
import com.rcdis.agent.dto.AuthRegisterRequest;

public interface AuthService {

    AuthLoginResponse login(AuthLoginRequest request);

    /**
     * Self-service sign-up: creates a researcher account and returns an already authenticated
     * session, so the user lands in the workbench without a second login. The role is fixed to
     * RESEARCHER and cannot be influenced by the request payload.
     */
    AuthLoginResponse register(AuthRegisterRequest request);
}
