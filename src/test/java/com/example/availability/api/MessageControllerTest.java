package com.example.availability.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.availability.service.AvailabilityProcessingService;
import com.example.availability.service.ProcessingResult;
import com.example.availability.service.ProductContextNotAvailableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

@SpringBootTest
@AutoConfigureMockMvc
class MessageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AvailabilityProcessingService processingService;

    @Test
    void acceptsValidInventoryEvent() throws Exception {
        when(processingService.process(any(SellerInventoryChanged.class)))
                .thenReturn(ProcessingResult.processed());

        mockMvc.perform(post("/_dev/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validEvent()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processed"));

        verify(processingService).process(any(SellerInventoryChanged.class));
    }

    @Test
    void rejectsInvalidEventWithoutProcessingIt() throws Exception {
        mockMvc.perform(post("/_dev/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validEvent().replace("\"availableQuantity\": 3",
                                "\"availableQuantity\":-1")))
                .andExpect(status().isBadRequest());

        verify(processingService, never()).process(any());
    }

    @Test
    void reportsSkippedVersionInResponse() throws Exception {
        when(processingService.process(any(SellerInventoryChanged.class)))
                .thenReturn(ProcessingResult.skipped("stale_version"));

        mockMvc.perform(post("/_dev/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validEvent()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("skipped"))
                .andExpect(jsonPath("$.reason").value("stale_version"));
    }

    @Test
    void returnsUnprocessableEntityWhenProductContextIsUnavailable() throws Exception {
        doThrow(new ProductContextNotAvailableException("Product is not available."))
                .when(processingService)
                .process(any());

        mockMvc.perform(post("/_dev/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validEvent()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Product is not available."));
    }

    @Test
    void returnsBadGatewayWhenADownstreamRequestFails() throws Exception {
        doThrow(new ResourceAccessException("Downstream service is unavailable."))
                .when(processingService)
                .process(any());

        mockMvc.perform(post("/_dev/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validEvent()))
                .andExpect(status().isBadGateway());
    }

    private String validEvent() {
        return """
                {
                  "eventId": "msg-0001",
                  "eventType": "SellerInventoryChanged",
                  "schemaVersion": 1,
                  "occurredAt": "2026-10-20T08:00:00Z",
                  "sellerId": "boutique-42",
                  "productId": "style-123",
                  "sizeId": "size-38",
                  "countryCode": "PT",
                  "availableQuantity": 3,
                  "sourceVersion": 7
                }
                """;
    }
}
