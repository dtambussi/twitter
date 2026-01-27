package com.twitter.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String DEFAULT_USER_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Twitter API")
                        .version("1.0")
                        .description("Twitter clone API"));
    }

    @Bean
    public OperationCustomizer addDefaultUserIdHeader() {
        return (operation, handlerMethod) -> {
            if (operation.getParameters() != null) {
                operation.getParameters().stream()
                        .filter(p -> "X-User-Id".equals(p.getName()))
                        .forEach(p -> {
                            // Set default value and example
                            StringSchema schema = new StringSchema();
                            schema.setDefault(DEFAULT_USER_ID);
                            schema.setExample(DEFAULT_USER_ID);
                            p.setSchema(schema);
                            p.setExample(DEFAULT_USER_ID);
                        });
            }
            return operation;
        };
    }
}
