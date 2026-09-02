package com.rcdis.agent.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.rcdis.agent.common.context.CurrentUserArgumentResolver;
import com.rcdis.agent.common.context.RequestContextInterceptor;
import com.rcdis.agent.service.FileStorageService;

import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfiguration implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUserArgumentResolver;
    private final RequestContextInterceptor requestContextInterceptor;
    private final FileStorageService fileStorageService;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requestContextInterceptor);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Serve uploaded files (invoice / payment proof images) from the local storage directory.
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(fileStorageService.uploadDir().toUri().toString());
    }
}
