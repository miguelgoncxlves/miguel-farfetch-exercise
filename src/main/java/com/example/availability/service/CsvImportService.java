package com.example.availability.service;

import com.example.availability.api.ImportAcceptedResponse;
import com.example.availability.api.ImportJobStatusResponse;
import com.example.availability.api.ImportRowResult;
import com.example.availability.api.SellerInventoryChanged;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.time.Clock;
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
    private final TaskExecutor executor;
    private final Validator validator;
    private final Clock clock;
    private final CsvImportFileParser fileParser = new CsvImportFileParser();
    private final Map<String, CsvImportJob> jobs = new LinkedHashMap<>();

    public CsvImportService(
            AvailabilityProcessingService processingService,
            @Qualifier("availabilityImportExecutor") TaskExecutor executor,
            Validator validator,
            Clock clock) {
        this.processingService = processingService;
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
            for (CsvImportRow row : rows) {
                job.record(processRow(job, row));
            }
            job.complete();
        } catch (RuntimeException exception) {
            logger.error("CSV import job {} stopped unexpectedly.", job.jobId(), exception);
            job.fail("The import stopped because of an unexpected processing error.");
        }
    }

    private ImportRowResult processRow(CsvImportJob job, CsvImportRow row) {
        SellerInventoryChanged event;
        try {
            event = toEvent(job.jobId(), row);
        } catch (RowValidationException exception) {
            return CsvImportJob.failed(row.rowNumber(), exception.getMessage());
        }

        Set<ConstraintViolation<SellerInventoryChanged>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; "));
            return CsvImportJob.failed(row.rowNumber(), message);
        }

        try {
            ProcessingResult result = processingService.process(event);
            if ("processed".equals(result.status())) {
                return CsvImportJob.succeeded(row.rowNumber());
            }
            if ("skipped".equals(result.status())) {
                return CsvImportJob.skipped(row.rowNumber(), result.reason());
            }
            throw new IllegalStateException("Unsupported processing result: " + result.status());
        } catch (ProductContextNotAvailableException | InvalidInventoryEventException exception) {
            return CsvImportJob.failed(row.rowNumber(), exception.getMessage());
        } catch (RestClientException exception) {
            return CsvImportJob.failed(row.rowNumber(), "A downstream service request failed.");
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
}
