package com.example.availability.api;

import com.example.availability.service.AvailabilityProcessingService;
import jakarta.validation.Valid;
import java.util.Map;
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
    public ResponseEntity<Map<String, String>> receive(
            @Valid @RequestBody SellerInventoryChanged event) {
        processingService.process(event);
        return ResponseEntity.ok(Map.of("status", "processed"));
    }
}
