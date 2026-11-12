package com.example.availability;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AvailabilityProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(AvailabilityProcessorApplication.class, args);
    }
}
