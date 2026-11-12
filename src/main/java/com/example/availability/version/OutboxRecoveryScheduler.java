package com.example.availability.version;

import com.example.availability.service.AvailabilityProcessingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "availability.outbox.recovery-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class OutboxRecoveryScheduler {

    private final AvailabilityProcessingService processingService;

    public OutboxRecoveryScheduler(AvailabilityProcessingService processingService) {
        this.processingService = processingService;
    }

    @Scheduled(
            fixedDelayString = "${availability.outbox.poll-delay-ms:5000}",
            initialDelayString = "${availability.outbox.initial-delay-ms:1000}")
    public void recoverPendingOperations() {
        processingService.recoverPendingOperations();
    }
}
