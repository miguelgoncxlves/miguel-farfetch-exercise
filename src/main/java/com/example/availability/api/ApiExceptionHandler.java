package com.example.availability.api;

import com.example.availability.service.InvalidInventoryEventException;
import com.example.availability.service.ImportCapacityExceededException;
import com.example.availability.service.ImportJobNotFoundException;
import com.example.availability.service.InvalidCsvImportException;
import com.example.availability.service.ProductContextNotAvailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidInventoryEventException.class)
    public ProblemDetail handleInvalidInventoryEvent(InvalidInventoryEventException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(ProductContextNotAvailableException.class)
    public ProblemDetail handleUnavailableProductContext(
            ProductContextNotAvailableException exception) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
    }

    @ExceptionHandler(RestClientException.class)
    public ProblemDetail handleDownstreamFailure(RestClientException exception) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, "A downstream service request failed.");
    }

    @ExceptionHandler(InvalidCsvImportException.class)
    public ProblemDetail handleInvalidCsvImport(InvalidCsvImportException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(ImportCapacityExceededException.class)
    public ProblemDetail handleImportCapacityExceeded(ImportCapacityExceededException exception) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
    }

    @ExceptionHandler(ImportJobNotFoundException.class)
    public ProblemDetail handleImportJobNotFound(ImportJobNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleUploadTooLarge(MaxUploadSizeExceededException exception) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.PAYLOAD_TOO_LARGE, "The uploaded CSV file exceeds the 10 MB limit.");
    }
}
