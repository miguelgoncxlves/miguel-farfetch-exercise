package com.example.availability.service;

public class ImportCapacityExceededException extends RuntimeException {

    public ImportCapacityExceededException(String message) {
        super(message);
    }

    public ImportCapacityExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
