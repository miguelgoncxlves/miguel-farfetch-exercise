package com.example.availability.service;

import com.example.availability.api.ImportJobStatusResponse;
import com.example.availability.api.ImportRowResult;
import com.example.availability.api.ImportRowStatus;
import java.util.ArrayList;
import java.util.List;

final class CsvImportJob {

    private final String jobId;
    private final int totalRows;
    private final List<ImportRowResult> rowResults = new ArrayList<>();
    private ImportJobState state = ImportJobState.QUEUED;
    private int succeededRows;
    private int skippedRows;
    private int failedRows;
    private String error;

    CsvImportJob(String jobId, int totalRows) {
        this.jobId = jobId;
        this.totalRows = totalRows;
    }

    String jobId() {
        return jobId;
    }

    synchronized boolean isTerminal() {
        return state.isTerminal();
    }

    synchronized void start() {
        state = ImportJobState.PROCESSING;
    }

    synchronized void record(ImportRowResult result) {
        rowResults.add(result);
        switch (result.status()) {
            case SUCCEEDED -> succeededRows++;
            case SKIPPED -> skippedRows++;
            case FAILED -> failedRows++;
        }
    }

    synchronized void complete() {
        state = failedRows == 0
                ? ImportJobState.COMPLETED
                : ImportJobState.COMPLETED_WITH_ERRORS;
    }

    synchronized void fail(String message) {
        state = ImportJobState.FAILED;
        error = message;
    }

    synchronized ImportJobStatusResponse snapshot() {
        return new ImportJobStatusResponse(
                jobId,
                state,
                totalRows,
                rowResults.size(),
                succeededRows,
                skippedRows,
                failedRows,
                error,
                List.copyOf(rowResults));
    }

    static ImportRowResult succeeded(int rowNumber) {
        return new ImportRowResult(rowNumber, ImportRowStatus.SUCCEEDED, null, null);
    }

    static ImportRowResult skipped(int rowNumber, String reason) {
        return new ImportRowResult(rowNumber, ImportRowStatus.SKIPPED, reason, null);
    }

    static ImportRowResult failed(int rowNumber, String message) {
        return new ImportRowResult(rowNumber, ImportRowStatus.FAILED, null, message);
    }
}
