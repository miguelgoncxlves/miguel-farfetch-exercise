package com.example.availability.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.availability.service.CsvImportService;
import com.example.availability.service.ImportCapacityExceededException;
import com.example.availability.service.ImportJobNotFoundException;
import com.example.availability.service.ImportJobState;
import com.example.availability.service.InvalidCsvImportException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

@SpringBootTest
@AutoConfigureMockMvc
class AvailabilityImportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CsvImportService importService;

    @Test
    void acceptsImportAndReturnsLocationForStatusPolling() throws Exception {
        String jobId = "import-job-123";
        String statusUrl = "/v1/availability/imports/" + jobId;
        when(importService.submit(any(MultipartFile.class)))
                .thenReturn(new ImportAcceptedResponse(
                        jobId, ImportJobState.QUEUED, 2, statusUrl));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "inventory.csv",
                "text/csv",
                """
                        sellerId,productId,sizeId,countryCode,availableQuantity,sourceVersion
                        seller-1,style-123,size-38,PT,3,7
                        seller-2,style-456,size-40,PT,0,1
                        """
                        .getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/v1/availability/imports").file(file))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", statusUrl))
                .andExpect(jsonPath("$.jobId").value(jobId))
                .andExpect(jsonPath("$.state").value("QUEUED"))
                .andExpect(jsonPath("$.totalRows").value(2));

        verify(importService).submit(any(MultipartFile.class));
    }

    @Test
    void returnsImportJobCountsAndRowResults() throws Exception {
        String jobId = "import-job-123";
        when(importService.status(jobId)).thenReturn(new ImportJobStatusResponse(
                jobId,
                ImportJobState.COMPLETED_WITH_ERRORS,
                2,
                2,
                1,
                0,
                1,
                null,
                List.of(
                        new ImportRowResult(2, ImportRowStatus.SUCCEEDED, null, null),
                        new ImportRowResult(3, ImportRowStatus.FAILED, null, "Invalid sourceVersion."))));

        mockMvc.perform(get("/v1/availability/imports/{jobId}", jobId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED_WITH_ERRORS"))
                .andExpect(jsonPath("$.totalRows").value(2))
                .andExpect(jsonPath("$.processedRows").value(2))
                .andExpect(jsonPath("$.succeededRows").value(1))
                .andExpect(jsonPath("$.failedRows").value(1))
                .andExpect(jsonPath("$.rowResults[1].rowNumber").value(3))
                .andExpect(jsonPath("$.rowResults[1].message").value("Invalid sourceVersion."));
    }

    @Test
    void returnsBadRequestForInvalidCsvAndNotFoundForUnknownJob() throws Exception {
        when(importService.submit(any(MultipartFile.class)))
                .thenThrow(new InvalidCsvImportException("Missing required CSV headers."));
        MockMultipartFile file = new MockMultipartFile(
                "file", "inventory.csv", "text/csv", "invalid".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/v1/availability/imports").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Missing required CSV headers."));

        when(importService.status("unknown-job"))
                .thenThrow(new ImportJobNotFoundException("unknown-job"));
        mockMvc.perform(get("/v1/availability/imports/unknown-job"))
                .andExpect(status().isNotFound());
    }

    @Test
    void returnsServiceUnavailableWhenImportQueueIsFull() throws Exception {
        when(importService.submit(any(MultipartFile.class)))
                .thenThrow(new ImportCapacityExceededException("The import queue is full."));
        MockMultipartFile file = new MockMultipartFile(
                "file", "inventory.csv", "text/csv", "valid csv".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/v1/availability/imports").file(file))
                .andExpect(status().isServiceUnavailable());
    }
}
