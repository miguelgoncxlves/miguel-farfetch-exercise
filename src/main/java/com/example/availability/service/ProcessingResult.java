package com.example.availability.service;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProcessingResult(String status, String reason) {

    public static ProcessingResult processed() {
        return new ProcessingResult("processed", null);
    }

    public static ProcessingResult skipped(String reason) {
        return new ProcessingResult("skipped", reason);
    }
}
