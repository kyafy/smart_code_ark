package com.smartark.gateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger metadata configuration.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI smartArkOpenApi(
            @Value("${spring.application.name:api-gateway}") String appName,
            @Value("${smartark.openapi.version:v1}") String apiVersion) {
        return new OpenAPI()
                .info(new Info()
                        .title(appName + " API")
                        .version(apiVersion)
                        .description("Smart Code Ark API gateway documentation. Includes model routing and admin APIs.")
                        .contact(new Contact().name("SmartArk Team"))
                        .license(new License().name("Proprietary")));
    }
}
