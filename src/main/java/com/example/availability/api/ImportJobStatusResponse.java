package com.example.availability.api;

import com.example.availability.service.ImportJobState;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImportJobStatusResponse(
        String jobId,
        ImportJobState state,
        int totalRows,
        int processedRows,
        int succeededRows,
        int skippedRows,
        int failedRows,
        String error,
        List<ImportRowResult> rowResults) {
}
