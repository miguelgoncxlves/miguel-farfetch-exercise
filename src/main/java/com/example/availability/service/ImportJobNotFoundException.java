package com.example.availability.service;

public class ImportJobNotFoundException extends RuntimeException {

    public ImportJobNotFoundException(String jobId) {
        super("No import job exists with ID " + jobId + ".");
    }
}
