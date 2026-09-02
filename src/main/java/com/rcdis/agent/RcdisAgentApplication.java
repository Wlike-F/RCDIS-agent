package com.rcdis.agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@MapperScan("com.rcdis.agent.mapper")
@SpringBootApplication
@ConfigurationPropertiesScan
public class RcdisAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(RcdisAgentApplication.class, args);
    }
}

