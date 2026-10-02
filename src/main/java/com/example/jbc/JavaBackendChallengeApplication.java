package com.example.jbc;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@OpenAPIDefinition(info = @Info(
        title = "Java Backend Challenge API", version = "0.1.0",
        description = "Sports training scheduling: create coaches, participants and sessions; "
                + "filter sessions, register participants, cancel registrations and delete empty sessions. "
                + "Timestamps require an explicit offset and return UTC at microsecond precision. "
                + "Errors use stable codes with optional fieldErrors. See README.md for a complete walkthrough."))
@SpringBootApplication
public class JavaBackendChallengeApplication {

    public static void main(String[] args) {
        SpringApplication.run(JavaBackendChallengeApplication.class, args);
    }
}
