package com.rcdis.agent.service;

import com.rcdis.agent.dto.AuthLoginRequest;
import com.rcdis.agent.dto.AuthLoginResponse;

public interface AuthService {

    AuthLoginResponse login(AuthLoginRequest request);
}
