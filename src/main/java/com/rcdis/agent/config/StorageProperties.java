package com.rcdis.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "rcdis.storage")
public class StorageProperties {

    /**
     * Local directory for uploaded files, e.g. invoice/payment proof images.
     * Files are served under the /uploads/** URL prefix.
     */
    private String uploadDir = "./uploads";
}
