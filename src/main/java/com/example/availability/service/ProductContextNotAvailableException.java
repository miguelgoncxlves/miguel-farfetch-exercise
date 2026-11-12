package com.example.availability.service;

public class ProductContextNotAvailableException extends RuntimeException {

    public ProductContextNotAvailableException(String message) {
        super(message);
    }
}
