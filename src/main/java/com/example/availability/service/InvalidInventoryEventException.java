package com.example.availability.service;

public class InvalidInventoryEventException extends RuntimeException {

    public InvalidInventoryEventException(String message) {
        super(message);
    }
}
