package org.finmath.classroom.web;

import java.util.Map;
import java.util.Optional;

import org.finmath.classroom.registration.RegistrationService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness and registration availability; final grading remains a separate unfinished capability. */
@RestController
public class StatusController {
    private final boolean enrollmentAvailable;

    public StatusController(Optional<RegistrationService> registration) {
        enrollmentAvailable = registration.isPresent();
    }
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "up");
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("application", "finmath-classroom", "implementation", "java",
                "phase", "registration", "enrollmentAvailable", enrollmentAvailable, "finalGradingAvailable", false);
    }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, String>> ready() {
        return ResponseEntity.status(enrollmentAvailable ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", enrollmentAvailable ? "ready" : "not-ready", "scope", "registration",
                        "reason", enrollmentAvailable ? "Registration configured; GitHub connectivity is checked on acceptance"
                                : "Registration requires private configuration, an imported database and GitHub credentials"));
    }
}
