package com.example.availability.service;

import com.example.availability.api.ImportAcceptedResponse;
import com.example.availability.api.ImportJobStatusResponse;
import com.example.availability.api.ImportRowResult;
import com.example.availability.api.SellerInventoryChanged;
import com.example.availability.client.ProductContextClient;
import com.example.availability.model.ProductContextSnapshotEntry;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CsvImportService {

    private static final Logger logger = LoggerFactory.getLogger(CsvImportService.class);
    private static final String STATUS_PATH = "/v1/availability/imports/";
    private static final int MAX_RETAINED_JOBS = 20;

    private final AvailabilityProcessingService processingService;
    private final ProductContextClient productContextClient;
    private final TaskExecutor executor;
    private final Validator validator;
    private final Clock clock;
    private final CsvImportFileParser fileParser = new CsvImportFileParser();
    private final Map<String, CsvImportJob> jobs = new LinkedHashMap<>();

    public CsvImportService(
            AvailabilityProcessingService processingService,
            ProductContextClient productContextClient,
            @Qualifier("availabilityImportExecutor") TaskExecutor executor,
            Validator validator,
            Clock clock) {
        this.processingService = processingService;
        this.productContextClient = productContextClient;
        this.executor = executor;
        this.validator = validator;
        this.clock = clock;
    }

    public ImportAcceptedResponse submit(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidCsvImportException("A non-empty CSV file is required.");
        }

        List<CsvImportRow> rows;
        try {
            rows = fileParser.parse(file.getBytes());
        } catch (IOException exception) {
            throw new InvalidCsvImportException("The uploaded CSV file could not be read.", exception);
        }

        String jobId = UUID.randomUUID().toString();
        CsvImportJob job = new CsvImportJob(jobId, rows.size());
        register(job);
        try {
            executor.execute(() -> process(job, rows));
        } catch (TaskRejectedException exception) {
            remove(jobId);
            throw new ImportCapacityExceededException(
                    "The import queue is full. Retry the upload later.", exception);
        }
        return new ImportAcceptedResponse(
                jobId, ImportJobState.QUEUED, rows.size(), STATUS_PATH + jobId);
    }

    public synchronized ImportJobStatusResponse status(String jobId) {
        CsvImportJob job = jobs.get(jobId);
        if (job == null) {
            throw new ImportJobNotFoundException(jobId);
        }
        return job.snapshot();
    }

    private synchronized void register(CsvImportJob job) {
        if (jobs.size() >= MAX_RETAINED_JOBS) {
            Iterator<Map.Entry<String, CsvImportJob>> iterator = jobs.entrySet().iterator();
            while (jobs.size() >= MAX_RETAINED_JOBS && iterator.hasNext()) {
                if (iterator.next().getValue().isTerminal()) {
                    iterator.remove();
                }
            }
        }
        if (jobs.size() >= MAX_RETAINED_JOBS) {
            throw new ImportCapacityExceededException(
                    "Import job capacity is full. Retry the upload later.");
        }
        jobs.put(job.jobId(), job);
    }

    private synchronized void remove(String jobId) {
        jobs.remove(jobId);
    }

    private void process(CsvImportJob job, List<CsvImportRow> rows) {
        job.start();
        try {
            List<PreparedRow> preparedRows = rows.stream()
                    .map(row -> prepareRow(job.jobId(), row))
                    .toList();
            Map<String, ProductContextSnapshotIndex> contextIndexes =
                    loadContextIndexes(preparedRows);
            for (PreparedRow row : preparedRows) {
                job.record(processRow(row, contextIndexes));
            }
            job.complete();
        } catch (RuntimeException exception) {
            logger.error("CSV import job {} stopped unexpectedly.", job.jobId(), exception);
            job.fail("The import stopped because of an unexpected processing error.");
        }
    }

    private PreparedRow prepareRow(String jobId, CsvImportRow row) {
        SellerInventoryChanged event;
        try {
            event = toEvent(jobId, row);
        } catch (RowValidationException exception) {
            return PreparedRow.failed(row, exception.getMessage());
        }

        Set<ConstraintViolation<SellerInventoryChanged>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; "));
            return PreparedRow.failed(row, message);
        }
        return PreparedRow.valid(row, event);
    }

    private Map<String, ProductContextSnapshotIndex> loadContextIndexes(
            List<PreparedRow> rows) {
        Map<String, Set<ProductContextKey>> requestedKeysByCountry = new LinkedHashMap<>();
        for (PreparedRow row : rows) {
            if (row.event() != null) {
                requestedKeysByCountry
                        .computeIfAbsent(row.event().countryCode(), ignored -> new HashSet<>())
                        .add(ProductContextSnapshotIndex.keyFor(row.event()));
            }
        }

        Map<String, ProductContextSnapshotIndex> indexes = new HashMap<>();
        for (Map.Entry<String, Set<ProductContextKey>> entry : requestedKeysByCountry.entrySet()) {
            String countryCode = entry.getKey();
            try {
                indexes.put(
                        countryCode,
                        loadContextIndex(countryCode, entry.getValue()));
            } catch (RestClientException exception) {
                logger.warn(
                        "Could not load Product Context snapshot for country {}.",
                        countryCode,
                        exception);
                indexes.put(countryCode, ProductContextSnapshotIndex.unavailable());
            }
        }
        return indexes;
    }

    private ProductContextSnapshotIndex loadContextIndex(
            String countryCode, Set<ProductContextKey> requestedKeys) {
        List<ProductContextSnapshotEntry> snapshot =
                productContextClient.getSnapshot(countryCode);
        return ProductContextSnapshotIndex.create(countryCode, snapshot, requestedKeys);
    }

    private ImportRowResult processRow(
            PreparedRow row, Map<String, ProductContextSnapshotIndex> contextIndexes) {
        if (row.failureMessage() != null) {
            return CsvImportJob.failed(row.row().rowNumber(), row.failureMessage());
        }

        SellerInventoryChanged event = row.event();
        ProductContextSnapshotIndex contextIndex = contextIndexes.get(event.countryCode());
        try {
            ProcessingResult result = processingService.process(
                    event, () -> contextIndex.resolve(event));
            if ("processed".equals(result.status())) {
                return CsvImportJob.succeeded(row.row().rowNumber());
            }
            if ("skipped".equals(result.status())) {
                return CsvImportJob.skipped(row.row().rowNumber(), result.reason());
            }
            throw new IllegalStateException("Unsupported processing result: " + result.status());
        } catch (ProductContextNotAvailableException | InvalidInventoryEventException exception) {
            return CsvImportJob.failed(row.row().rowNumber(), exception.getMessage());
        } catch (RestClientException exception) {
            return CsvImportJob.failed(
                    row.row().rowNumber(), "A downstream service request failed.");
        }
    }

    private SellerInventoryChanged toEvent(String jobId, CsvImportRow row) {
        int availableQuantity;
        try {
            availableQuantity = Integer.parseInt(row.availableQuantity());
        } catch (NumberFormatException exception) {
            throw new RowValidationException("availableQuantity must be a non-negative integer.");
        }

        long sourceVersion;
        try {
            sourceVersion = Long.parseLong(row.sourceVersion());
        } catch (NumberFormatException exception) {
            throw new RowValidationException("sourceVersion must be a non-negative integer.");
        }

        return new SellerInventoryChanged(
                jobId + ":" + row.rowNumber(),
                "SellerInventoryChanged",
                1,
                clock.instant(),
                row.sellerId(),
                row.productId(),
                row.sizeId(),
                row.countryCode(),
                availableQuantity,
                sourceVersion);
    }

    private static final class RowValidationException extends RuntimeException {

        private RowValidationException(String message) {
            super(message);
        }
    }

    private record PreparedRow(
            CsvImportRow row, SellerInventoryChanged event, String failureMessage) {

        private static PreparedRow valid(CsvImportRow row, SellerInventoryChanged event) {
            return new PreparedRow(row, event, null);
        }

        private static PreparedRow failed(CsvImportRow row, String message) {
            return new PreparedRow(row, null, message);
        }
    }
}
