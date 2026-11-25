package com.zw.sserver.controller;

import com.zw.sserver.config.DeliveryOpsAccessInterceptor;
import com.zw.sserver.config.DeliveryOpsConfiguration;
import com.zw.sserver.config.DeliveryOpsProperties;
import com.zw.sserver.service.DeliveryOperationsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DeliveryOperationsControllerTest {

    private static final String SECRET = "ops-test-secret-not-for-production-0123456789";
    private DeliveryOperationsService service;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        service = mock(DeliveryOperationsService.class);
        mvc = mvc(service);
    }

    @Test
    void missingWrongAndOrdinaryCredentialsCannotAccessOperations() throws Exception {
        mvc.perform(get("/ops/delivery/summary")).andExpect(status().isUnauthorized());
        mvc.perform(get("/ops/delivery/summary").header("X-SChat-Ops-Token", "wrong"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/ops/delivery/summary").header("Authorization", "Bearer ordinary-jwt").header("X-Internal-Token", "internal-secret"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void authenticationAlsoRequiresAValidActorLabel() throws Exception {
        mvc.perform(get("/ops/delivery/summary").header("X-SChat-Ops-Token", SECRET))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/ops/delivery/summary").header("X-SChat-Ops-Token", SECRET).header("X-SChat-Ops-Actor", "invalid actor"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void authenticatedSummaryReturnsOnlyMetricsAndServerGeneratedRequestId() throws Exception {
        when(service.summary()).thenReturn(new DeliveryOperationsService.Snapshot(Map.of("FAILED", 2L), 2, 4, 10, 0, 0));
        mvc.perform(get("/ops/delivery/summary").header("X-SChat-Ops-Token", SECRET).header("X-SChat-Ops-Actor", "oncall:test"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.failedCount").value(2))
                .andExpect(header().exists("X-SChat-Ops-Request-Id"));
    }

    @Test
    void retryPassesAuthenticatedIdentityAndGeneratedRequestToAudit() throws Exception {
        when(service.retry(anyList(), anyString(), anyString(), anyString())).thenReturn(List.of());
        mvc.perform(post("/ops/delivery/retry").header("X-SChat-Ops-Token", SECRET).header("X-SChat-Ops-Actor", "oncall")
                        .contentType("application/json").content("{\"items\":[{\"id\":\"9\",\"revision\":1}],\"reason\":\"route repaired\"}"))
                .andExpect(status().isOk());
        verify(service).retry(eq(List.of(new DeliveryOperationsService.RetryItem("9", 1))), eq("route repaired"), eq("oncall"), matches("[a-f0-9-]{36}"));
    }

    @Test
    void invalidParametersAndMalformedBodiesReturnActualHttp400() throws Exception {
        var real = new DeliveryOperationsService(mock(JdbcTemplate.class), mock(PlatformTransactionManager.class));
        var validated = mvc(real);
        for (String path : List.of("/failed?limit=101", "/audit?afterId=-1", "/cleanup?olderThanDays=0", "/failed?afterId=invalid")) {
            validated.perform(get("/ops/delivery" + path).header("X-SChat-Ops-Token", SECRET).header("X-SChat-Ops-Actor", "oncall"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        }
        for (String body : List.of("{", "{}", "{\"items\":[],\"reason\":\"repair\"}")) {
            validated.perform(post("/ops/delivery/retry").header("X-SChat-Ops-Token", SECRET).header("X-SChat-Ops-Actor", "oncall")
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        }
    }

    @Test
    void databaseErrorsDoNotLeakPayloadOrConnectionDetails() throws Exception {
        when(service.summary()).thenThrow(new DataAccessResourceFailureException("sensitive connection details"));
        mvc.perform(get("/ops/delivery/summary").header("X-SChat-Ops-Token", SECRET).header("X-SChat-Ops-Actor", "oncall"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(503))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sensitive"))));
        when(service.retry(anyList(), anyString(), anyString(), anyString()))
                .thenThrow(new org.springframework.transaction.CannotCreateTransactionException("sensitive connection details"));
        mvc.perform(post("/ops/delivery/retry").header("X-SChat-Ops-Token", SECRET).header("X-SChat-Ops-Actor", "oncall")
                        .contentType("application/json").content("{\"items\":[{\"id\":\"9\",\"revision\":1}],\"reason\":\"route repaired\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(503))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sensitive"))));
    }

    @Test
    void unsafeConfigurationFailsClosed() {
        var properties = new DeliveryOpsProperties();
        properties.validate();
        properties.setEnabled(true);
        assertThrows(IllegalStateException.class, properties::validate);
        properties.setSecret(SECRET);
        properties.validate();
        assertThrows(IllegalStateException.class, () -> new DeliveryOpsConfiguration(properties, SECRET, "jwt"));
        assertThrows(IllegalStateException.class, () -> new DeliveryOpsConfiguration(properties, "internal", SECRET));
        assertDoesNotThrow(() -> new DeliveryOpsConfiguration(properties, "internal", "jwt"));
        properties.setEnabled(false);
        properties.setAutoCleanupEnabled(true);
        assertThrows(IllegalStateException.class, properties::validate);
    }

    private static MockMvc mvc(DeliveryOperationsService target) {
        return MockMvcBuilders.standaloneSetup(new DeliveryOperationsController(target))
                .addInterceptors(new DeliveryOpsAccessInterceptor(SECRET)).build();
    }
}
