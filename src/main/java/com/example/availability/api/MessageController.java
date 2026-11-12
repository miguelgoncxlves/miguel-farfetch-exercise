package com.example.availability.api;

import com.example.availability.service.AvailabilityProcessingService;
import com.example.availability.service.ProcessingResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MessageController {

    private final AvailabilityProcessingService processingService;

    public MessageController(AvailabilityProcessingService processingService) {
        this.processingService = processingService;
    }

    @PostMapping("/_dev/messages")
    public ResponseEntity<ProcessingResult> receive(
            @Valid @RequestBody SellerInventoryChanged event) {
        return ResponseEntity.ok(processingService.process(event));
    }
}
