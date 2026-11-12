package com.example.availability.api;

import com.example.availability.service.CsvImportService;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class AvailabilityImportController {

    private final CsvImportService importService;

    public AvailabilityImportController(CsvImportService importService) {
        this.importService = importService;
    }

    @PostMapping(
            path = "/v1/availability/imports",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportAcceptedResponse> submit(
            @RequestParam("file") MultipartFile file) {
        ImportAcceptedResponse response = importService.submit(file);
        return ResponseEntity.accepted()
                .location(URI.create(response.statusUrl()))
                .body(response);
    }

    @GetMapping("/v1/availability/imports/{jobId}")
    public ImportJobStatusResponse status(@PathVariable String jobId) {
        return importService.status(jobId);
    }
}
