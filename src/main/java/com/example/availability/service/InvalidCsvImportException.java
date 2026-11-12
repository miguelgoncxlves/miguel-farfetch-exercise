package com.example.availability.service;

public class InvalidCsvImportException extends RuntimeException {

    public InvalidCsvImportException(String message) {
        super(message);
    }

    public InvalidCsvImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
