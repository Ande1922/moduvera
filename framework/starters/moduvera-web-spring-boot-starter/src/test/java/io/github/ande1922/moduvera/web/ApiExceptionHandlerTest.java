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
                .addFilters(new CorrelationIdFilter(true))
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

    @Test
    void preservesSpecificMappingsInsideWrappedExceptions() throws Exception {
        mockMvc.perform(get("/wrapped-failure"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("order.stock-rejected"));
        mockMvc.perform(get("/wrapped-validation"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("request.validation-failed"));
    }

    @Test
    void keepsCommittedResponseWhenUnexpectedFailureArrives() throws Exception {
        mockMvc.perform(get("/committed"))
                .andExpect(status().isAccepted())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("started"));
    }

    @RestController
    private static final class FailingController {

        @GetMapping("/failure")
        String fail() {
            throw new CodedException(new ErrorCode("order.stock-rejected"), "Stock was rejected");
        }

        @GetMapping("/wrapped-failure")
        String wrappedFailure() throws jakarta.servlet.ServletException {
            throw new jakarta.servlet.ServletException(
                    new CodedException(new ErrorCode("order.stock-rejected"), "Stock was rejected"));
        }

        @GetMapping("/wrapped-validation")
        String wrappedValidation() throws jakarta.servlet.ServletException {
            throw new jakarta.servlet.ServletException(
                    new jakarta.validation.ConstraintViolationException(java.util.Set.of()));
        }

        @GetMapping("/committed")
        void committed(jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
            response.setStatus(202);
            response.getWriter().write("started");
            response.flushBuffer();
            throw new IllegalStateException("after response commit");
        }
    }
}
