package com.smartark.gateway.config;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer customStreamReadConstraints() {
        return builder -> builder.postConfigurer(objectMapper -> {
            objectMapper.getFactory().setStreamReadConstraints(
                    StreamReadConstraints.builder()
                            .maxStringLength(50_000_000) // Increase to 50MB
                            .build()
            );
        });
    }
}
