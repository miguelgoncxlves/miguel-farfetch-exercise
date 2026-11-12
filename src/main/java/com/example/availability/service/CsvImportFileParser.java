package com.example.availability.service;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

final class CsvImportFileParser {

    private static final List<String> REQUIRED_HEADERS = List.of(
            "sellerId",
            "productId",
            "sizeId",
            "countryCode",
            "availableQuantity",
            "sourceVersion");

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .setTrim(true)
            .get();

    List<CsvImportRow> parse(byte[] content) {
        if (content.length == 0) {
            throw new InvalidCsvImportException("The uploaded CSV file is empty.");
        }

        try (PushbackReader reader = utf8Reader(content);
                CSVParser parser = FORMAT.parse(reader)) {
            List<String> headers = parser.getHeaderNames().stream()
                    .map(String::trim)
                    .toList();
            validateHeaders(headers);

            Map<String, Integer> headerIndexes = headerIndexes(headers);
            List<CSVRecord> records = parser.getRecords();
            List<CsvImportRow> rows = new ArrayList<>(records.size());
            int rowNumber = 1;
            for (CSVRecord record : records) {
                rowNumber++;
                if (!record.isConsistent()) {
                    throw new InvalidCsvImportException(
                            "CSV record " + rowNumber + " has a different number of columns than the header.");
                }
                rows.add(new CsvImportRow(
                        rowNumber,
                        value(record, headerIndexes, "sellerId"),
                        value(record, headerIndexes, "productId"),
                        value(record, headerIndexes, "sizeId"),
                        value(record, headerIndexes, "countryCode"),
                        value(record, headerIndexes, "availableQuantity"),
                        value(record, headerIndexes, "sourceVersion")));
            }
            if (rows.isEmpty()) {
                throw new InvalidCsvImportException("The CSV file must contain at least one data row.");
            }
            return List.copyOf(rows);
        } catch (IOException | UncheckedIOException | IllegalArgumentException exception) {
            throw new InvalidCsvImportException("The uploaded CSV file is malformed.", exception);
        }
    }

    private PushbackReader utf8Reader(byte[] content) throws IOException {
        PushbackReader reader = new PushbackReader(
                new BufferedReader(new InputStreamReader(
                        new ByteArrayInputStream(content), StandardCharsets.UTF_8)),
                1);
        int firstCharacter = reader.read();
        if (firstCharacter != '\uFEFF' && firstCharacter != -1) {
            reader.unread(firstCharacter);
        }
        return reader;
    }

    private void validateHeaders(List<String> headers) {
        if (headers.isEmpty() || headers.stream().anyMatch(String::isBlank)) {
            throw new InvalidCsvImportException("The CSV file must include a header row.");
        }
        if (new HashSet<>(headers).size() != headers.size()) {
            throw new InvalidCsvImportException("The CSV file contains duplicate headers.");
        }

        List<String> missingHeaders = REQUIRED_HEADERS.stream()
                .filter(header -> !headers.contains(header))
                .toList();
        if (!missingHeaders.isEmpty()) {
            throw new InvalidCsvImportException(
                    "The CSV file is missing required headers: " + String.join(", ", missingHeaders) + ".");
        }
    }

    private Map<String, Integer> headerIndexes(List<String> headers) {
        Map<String, Integer> indexes = new HashMap<>();
        for (int index = 0; index < headers.size(); index++) {
            indexes.put(headers.get(index), index);
        }
        return indexes;
    }

    private String value(CSVRecord record, Map<String, Integer> headerIndexes, String header) {
        return record.get(headerIndexes.get(header));
    }
}
