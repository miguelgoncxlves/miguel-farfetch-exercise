package com.example.availability.client;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class DownstreamRetryExecutor {

    private final int maxAttempts;
    private final Duration baseDelay;
    private final Clock clock;
    private final Consumer<Duration> delay;

    @Autowired
    public DownstreamRetryExecutor(
            Clock clock,
            @Value("${availability.retry.max-attempts:3}") int maxAttempts,
            @Value("${availability.retry.base-delay:100ms}") Duration baseDelay) {
        this(clock, maxAttempts, baseDelay, DownstreamRetryExecutor::sleep);
    }

    public DownstreamRetryExecutor(
            Clock clock,
            int maxAttempts,
            Duration baseDelay,
            Consumer<Duration> delay) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least one.");
        }
        if (baseDelay.isNegative()) {
            throw new IllegalArgumentException("baseDelay must not be negative.");
        }
        this.maxAttempts = maxAttempts;
        this.baseDelay = baseDelay;
        this.clock = clock;
        this.delay = delay;
    }

    public <T> T execute(Supplier<T> request) {
        for (int attempt = 1; ; attempt++) {
            try {
                return request.get();
            } catch (RestClientResponseException exception) {
                if (!isRetryable(exception) || attempt >= maxAttempts) {
                    throw exception;
                }
                delay.accept(retryDelay(exception, attempt));
            }
        }
    }

    private boolean isRetryable(RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        return status == HttpStatus.TOO_MANY_REQUESTS.value()
                || status == HttpStatus.SERVICE_UNAVAILABLE.value();
    }

    private Duration retryDelay(RestClientResponseException exception, int attempt) {
        HttpHeaders headers = exception.getResponseHeaders();
        String retryAfter = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (retryAfter != null) {
            Duration serverDelay = parseRetryAfter(retryAfter);
            if (serverDelay != null) {
                return serverDelay;
            }
        }
        return baseDelay.multipliedBy(1L << Math.min(attempt - 1, 30));
    }

    private Duration parseRetryAfter(String retryAfter) {
        try {
            long seconds = Long.parseLong(retryAfter);
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException ignored) {
            try {
                Duration duration = Duration.between(
                        clock.instant(),
                        ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME)
                                .toInstant());
                return duration.isNegative() ? Duration.ZERO : duration;
            } catch (DateTimeParseException ignoredDate) {
                return null;
            }
        }
    }

    private static void sleep(Duration duration) {
        if (duration.isZero()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RestClientException("Interrupted while waiting to retry a downstream call.",
                    exception);
        }
    }
}
