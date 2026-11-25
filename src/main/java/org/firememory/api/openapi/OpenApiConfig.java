package org.firememory.api.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API-level OpenAPI metadata (title, version, description) for the generated spec at
 * {@code /v3/api-docs}. Endpoint docs live on the {@code *Api} interfaces in this package.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI fireGroundMemoryOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Fireground Memory API")
                .version("v1")
                .description("Real-time transcription of fireground radio transmissions."));
    }
}
