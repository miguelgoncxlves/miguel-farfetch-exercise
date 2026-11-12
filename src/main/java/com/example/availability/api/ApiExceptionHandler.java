package com.example.availability.api;

import com.example.availability.service.InvalidInventoryEventException;
import com.example.availability.service.ProductContextNotAvailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

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
}
