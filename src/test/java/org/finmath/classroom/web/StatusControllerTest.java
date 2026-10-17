package org.finmath.classroom.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class StatusControllerTest {
    @Autowired WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test void reportsLivenessWithoutClaimingCourseReadiness() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("up"));
        mvc.perform(get("/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.enrollmentAvailable").value(false))
                .andExpect(jsonPath("$.finalGradingAvailable").value(false));
        mvc.perform(get("/ready")).andExpect(status().isServiceUnavailable());
    }

    @Test void noEnrollmentOrWebhookEndpointsAreAccidentallyExposed() throws Exception {
        mvc.perform(get("/accept")).andExpect(status().isNotFound());
        mvc.perform(post("/webhook")).andExpect(status().isNotFound());
    }
}
