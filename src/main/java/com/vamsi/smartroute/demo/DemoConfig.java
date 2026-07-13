package com.vamsi.smartroute.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * Active only under the {@code demo} profile. Supplies a {@link DemoChatModel} as the primary
 * {@link ChatModel} so every route/gateway call is served locally with no API key — see
 * {@code application-demo.yml}, which also defaults the OpenAI key so startup never requires one.
 *
 * Run it: {@code ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo}
 */
@Configuration
@Profile("demo")
public class DemoConfig {

    private static final Logger log = LoggerFactory.getLogger(DemoConfig.class);

    @Bean
    @Primary
    ChatModel demoChatModel() {
        log.info("SmartRoute is running in DEMO mode: responses are canned, no live model calls are made.");
        return new DemoChatModel();
    }
}
