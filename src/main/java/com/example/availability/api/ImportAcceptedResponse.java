package com.example.availability.api;

import com.example.availability.service.ImportJobState;

public record ImportAcceptedResponse(
        String jobId, ImportJobState state, int totalRows, String statusUrl) {
}
