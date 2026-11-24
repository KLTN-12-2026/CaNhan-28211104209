package com.carrental.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@ExtendWith(OutputCaptureExtension.class)
class ApiErrorsTest {
    private static final String SENTINEL = "private-value-sentinel";

    @RestController
    static class FailingController {
        @GetMapping("/npe") Object npe() { throw new NullPointerException(SENTINEL); }
        @GetMapping("/iae") Object iae() { throw new IllegalArgumentException(SENTINEL); }
        @GetMapping("/integrity") Object integrity() { throw new DataIntegrityViolationException("Duplicate entry '" + SENTINEL + "'"); }
        @GetMapping("/domain") Object domain() { throw DomainException.conflict("Xe đã có đơn trùng khoảng thuê."); }
        @PostMapping("/json") Object json(@RequestBody Map<String, Object> body) { return body; }
    }

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new FailingController()).setControllerAdvice(new ApiErrors()).build();
    }

    private JsonNode body(String content) throws Exception { return json.readTree(content); }

    @Test
    void nullPointerIsReportedAsServerErrorWithReferenceAndLoggedStackTrace(CapturedOutput output) throws Exception {
        var response = mvc.perform(get("/npe")).andReturn().getResponse();

        assertEquals(500, response.getStatus());
        var document = body(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        String reference = document.path("reference").asText();
        assertTrue(reference.matches("[0-9a-f]{8}"), reference);
        assertTrue(document.path("message").asText().contains(reference));
        assertFalse(response.getContentAsString().contains(SENTINEL), "internal exception text must not reach the client");

        String log = output.getOut() + output.getErr();
        assertTrue(log.contains("api_server_error status=500 reference=" + reference + " method=GET path=/npe"), log);
        assertTrue(log.contains("java.lang.NullPointerException"), "stack trace must be logged");
        assertTrue(log.contains("FailingController.npe"), "stack trace must point at the failing frame");
    }

    @Test
    void eachServerErrorGetsItsOwnReference() throws Exception {
        String first = body(mvc.perform(get("/npe")).andReturn().getResponse().getContentAsString()).path("reference").asText();
        String second = body(mvc.perform(get("/npe")).andReturn().getResponse().getContentAsString()).path("reference").asText();
        assertNotEquals(first, second);
    }

    @Test
    void illegalArgumentStaysClientErrorAndLogsOnlyItsType(CapturedOutput output) throws Exception {
        var response = mvc.perform(get("/iae")).andReturn().getResponse();

        assertEquals(400, response.getStatus());
        assertEquals("Định dạng dữ liệu không hợp lệ.", body(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("message").asText());
        String log = output.getOut() + output.getErr();
        assertTrue(log.contains("api_rejected status=400 type=java.lang.IllegalArgumentException method=GET path=/iae"), log);
        assertFalse(log.contains(SENTINEL), "exception text may echo request values and must not be logged");
        assertFalse(log.contains("api_server_error"));
    }

    @Test
    void malformedJsonStaysClientError() throws Exception {
        var response = mvc.perform(post("/json").contentType(MediaType.APPLICATION_JSON).content("{not-json")).andReturn().getResponse();

        assertEquals(400, response.getStatus());
        assertEquals("Định dạng dữ liệu không hợp lệ.", body(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("message").asText());
    }

    @Test
    void integrityViolationStaysConflictWithoutLoggingRowValues(CapturedOutput output) throws Exception {
        var response = mvc.perform(get("/integrity")).andReturn().getResponse();

        assertEquals(409, response.getStatus());
        String log = output.getOut() + output.getErr();
        assertTrue(log.contains("api_rejected status=409 type=org.springframework.dao.DataIntegrityViolationException"), log);
        assertFalse(log.contains(SENTINEL));
    }

    @Test
    void domainExceptionKeepsItsStatusAndMessage(CapturedOutput output) throws Exception {
        var response = mvc.perform(get("/domain")).andReturn().getResponse();

        assertEquals(409, response.getStatus());
        assertEquals("Xe đã có đơn trùng khoảng thuê.", body(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("message").asText());
        assertFalse((output.getOut() + output.getErr()).contains("api_server_error"));
    }
}
