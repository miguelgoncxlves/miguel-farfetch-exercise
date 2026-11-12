package com.example.availability.api;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImportRowResult(
        int rowNumber, ImportRowStatus status, String reason, String message) {
}
