package com.rcdis.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI rcdisOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("RCDIS Agent API")
                        .version("0.1.0")
                        .description("Laboratory research fund management agent API"));
    }
}

