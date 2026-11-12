package com.example.availability.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.availability.api.ImportAcceptedResponse;
import com.example.availability.api.ImportJobStatusResponse;
import com.example.availability.api.ImportRowStatus;
import com.example.availability.api.SellerInventoryChanged;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class CsvImportServiceTest {

    @Mock
    private AvailabilityProcessingService processingService;

    private Validator validator;
    private ValidatorFactory validatorFactory;

    private CapturingTaskExecutor executor;
    private CsvImportService importService;

    @BeforeEach
    void setUp() {
        executor = new CapturingTaskExecutor();
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
        importService = new CsvImportService(
                processingService,
                executor,
                validator,
                Clock.fixed(Instant.parse("2026-10-20T08:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        validatorFactory.close();
    }

    @Test
    void acceptsCsvAndProcessesRowsAsynchronouslyWithPartialFailures() {
        ImportAcceptedResponse accepted = importService.submit(csv("""
                sellerId,productId,sizeId,countryCode,availableQuantity,sourceVersion
                seller-good,style-123,size-38,PT,3,7
                seller-invalid,style-123,size-38,PT,not-a-number,7
                seller-negative,style-123,size-38,pt,-1,7
                seller-stale,style-123,size-38,PT,0,6
                seller-context,style-123,size-38,PT,5,7
                seller-last,style-123,size-38,PT,4,8
                """));

        assertThat(accepted.state()).isEqualTo(ImportJobState.QUEUED);
        assertThat(accepted.totalRows()).isEqualTo(6);
        assertThat(importService.status(accepted.jobId()).processedRows()).isZero();
        assertThat(executor.tasks).hasSize(1);

        when(processingService.process(any(SellerInventoryChanged.class)))
                .thenAnswer(invocation -> {
                    SellerInventoryChanged event = invocation.getArgument(0);
                    return switch (event.sellerId()) {
                        case "seller-stale" -> ProcessingResult.skipped("stale_version");
                        case "seller-context" -> throw new ProductContextNotAvailableException(
                                "No product context exists for the requested product, size, and country.");
                        default -> ProcessingResult.processed();
                    };
                });
        executor.runNext();

        ImportJobStatusResponse status = importService.status(accepted.jobId());
        assertThat(status.state()).isEqualTo(ImportJobState.COMPLETED_WITH_ERRORS);
        assertThat(status.totalRows()).isEqualTo(6);
        assertThat(status.processedRows()).isEqualTo(6);
        assertThat(status.succeededRows()).isEqualTo(2);
        assertThat(status.skippedRows()).isEqualTo(1);
        assertThat(status.failedRows()).isEqualTo(3);
        assertThat(status.rowResults())
                .extracting(result -> result.status())
                .containsExactly(
                        ImportRowStatus.SUCCEEDED,
                        ImportRowStatus.FAILED,
                        ImportRowStatus.FAILED,
                        ImportRowStatus.SKIPPED,
                        ImportRowStatus.FAILED,
                        ImportRowStatus.SUCCEEDED);
        assertThat(status.rowResults().get(1).rowNumber()).isEqualTo(3);
        assertThat(status.rowResults().get(1).message())
                .isEqualTo("availableQuantity must be a non-negative integer.");
        assertThat(status.rowResults().get(2).rowNumber()).isEqualTo(4);
        assertThat(status.rowResults().get(2).message())
                .contains("availableQuantity", "countryCode");
        assertThat(status.rowResults().get(3).reason()).isEqualTo("stale_version");
        assertThat(status.rowResults().get(4).message())
                .contains("No product context exists");
        ArgumentCaptor<SellerInventoryChanged> events =
                ArgumentCaptor.forClass(SellerInventoryChanged.class);
        verify(processingService, times(4)).process(events.capture());
        assertThat(events.getAllValues().get(0))
                .satisfies(event -> {
                    assertThat(event.eventId()).isEqualTo(accepted.jobId() + ":2");
                    assertThat(event.sellerId()).isEqualTo("seller-good");
                    assertThat(event.productId()).isEqualTo("style-123");
                    assertThat(event.availableQuantity()).isEqualTo(3);
                    assertThat(event.sourceVersion()).isEqualTo(7);
                });
    }

    @Test
    void rejectsMissingHeadersBeforeCreatingAJob() {
        assertThatThrownBy(() -> importService.submit(csv("""
                sellerId,productId,sizeId,countryCode,availableQuantity
                seller-1,style-123,size-38,PT,3
                """)))
                .isInstanceOf(InvalidCsvImportException.class)
                .hasMessageContaining("sourceVersion");

        assertThat(executor.tasks).isEmpty();
    }

    @Test
    void rejectsMalformedRowsAndHeaderOnlyFilesSynchronously() {
        assertThatThrownBy(() -> importService.submit(csv("""
                sellerId,productId,sizeId,countryCode,availableQuantity,sourceVersion
                seller-1,style-123,size-38,PT,3
                """)))
                .isInstanceOf(InvalidCsvImportException.class)
                .hasMessageContaining("different number of columns");

        assertThatThrownBy(() -> importService.submit(csv(
                "sellerId,productId,sizeId,countryCode,availableQuantity,sourceVersion\n")))
                .isInstanceOf(InvalidCsvImportException.class)
                .hasMessageContaining("at least one data row");
        assertThat(executor.tasks).isEmpty();
    }

    @Test
    void reportsQueueSaturationWithoutLeavingAnUnreachableJob() {
        TaskExecutor rejectingExecutor = task -> {
            throw new TaskRejectedException("queue full");
        };
        CsvImportService saturatedService = new CsvImportService(
                processingService,
                rejectingExecutor,
                validator,
                Clock.systemUTC());

        assertThatThrownBy(() -> saturatedService.submit(csv("""
                sellerId,productId,sizeId,countryCode,availableQuantity,sourceVersion
                seller-1,style-123,size-38,PT,3,7
                """)))
                .isInstanceOf(ImportCapacityExceededException.class);
    }

    @Test
    void retainsOnlyTheLatestTwentyCompletedJobStatuses() {
        when(processingService.process(any(SellerInventoryChanged.class)))
                .thenReturn(ProcessingResult.processed());
        List<String> jobIds = new ArrayList<>();

        for (int index = 0; index < 21; index++) {
            ImportAcceptedResponse accepted = importService.submit(csv("""
                    sellerId,productId,sizeId,countryCode,availableQuantity,sourceVersion
                    seller-%d,style-123,size-38,PT,3,1
                    """.formatted(index)));
            jobIds.add(accepted.jobId());
            executor.runNext();
        }

        assertThatThrownBy(() -> importService.status(jobIds.get(0)))
                .isInstanceOf(ImportJobNotFoundException.class);
        assertThat(importService.status(jobIds.get(20)).state())
                .isEqualTo(ImportJobState.COMPLETED);
    }

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile(
                "file",
                "inventory.csv",
                "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    private static final class CapturingTaskExecutor implements TaskExecutor {

        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        private void runNext() {
            tasks.remove(0).run();
        }
    }
}
