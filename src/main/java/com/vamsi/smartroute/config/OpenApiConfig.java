package com.vamsi.smartroute.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger UI at /swagger-ui.html, raw spec at /v3/api-docs. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI smartRouteOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SmartRoute — AI Gateway")
                        .description("""
                                Spring AI control plane in front of every LLM call: routes GPT-5.6 prompts \
                                across the Luna/Terra/Sol price tiers, then wraps that router with guardrails \
                                (prompt-injection scanning, tool-drift detection), governance (per-tenant \
                                budget caps), and observability (cost + latency telemetry).""")
                        .version("0.1.0")
                        .contact(new Contact().name("Vamsi Duppala"))
                        .license(new License().name("MIT")));
    }
}
