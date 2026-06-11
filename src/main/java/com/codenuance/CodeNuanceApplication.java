package com.codenuance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CodeNuance — a real-time collaborative code editor.
 *
 * <p>Spring Boot serves a single-page editor over WebSockets and merges
 * concurrent edits with an Operational Transformation engine ({@code com.codenuance.ot}).
 */
@SpringBootApplication
public class CodeNuanceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodeNuanceApplication.class, args);
    }
}
