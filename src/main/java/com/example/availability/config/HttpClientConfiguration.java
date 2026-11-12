package com.example.availability.config;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClientConfiguration {

    @Bean
    RestClient restClient(
            RestClient.Builder builder,
            @Value("${wiremock.base-url}") String wiremockBaseUrl) {
        return builder.baseUrl(wiremockBaseUrl).build();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
