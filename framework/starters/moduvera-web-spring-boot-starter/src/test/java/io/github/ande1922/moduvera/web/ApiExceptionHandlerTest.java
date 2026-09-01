package io.github.ande1922.moduvera.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.ande1922.moduvera.error.CodedException;
import io.github.ande1922.moduvera.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class ApiExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new ApiExceptionHandler(exception -> HttpStatus.UNPROCESSABLE_CONTENT))
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    @Test
    void rendersStableProblemDetailsWithoutWrappingSuccessSemantics() throws Exception {
        mockMvc.perform(get("/failure").header(CorrelationIdFilter.HEADER, "corr-42"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().string(CorrelationIdFilter.HEADER, "corr-42"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.code").value("order.stock-rejected"))
                .andExpect(jsonPath("$.correlationId").value("corr-42"));
    }

    @RestController
    private static final class FailingController {

        @GetMapping("/failure")
        String fail() {
            throw new CodedException(new ErrorCode("order.stock-rejected"), "Stock was rejected");
        }
    }
}
