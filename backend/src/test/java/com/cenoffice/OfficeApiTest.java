package com.cenoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:officetest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "app.seed.admin-password=test-admin-pw-1234",
        "app.seed.user-password=test-user-pw-1234",
        "app.jwt.secret=test-secret-test-secret-test-secret-123456",
        "app.devices.mock.min-latency-ms=150",
        "app.devices.mock.max-latency-ms=200",
        "app.devices.mock.failure-rate=0.0",
        "app.devices.command-timeout-seconds=5"})
@AutoConfigureMockMvc
class OfficeApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    String token;

    @BeforeEach
    void login() throws Exception {
        token = loginAs("user", "test-user-pw-1234");
    }

    String loginAs(String u, String p) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    @Test
    void rejectsAnonymousAndBadPassword() throws Exception {
        mvc.perform(get("/api/devices")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"user\",\"password\":\"wrong\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void lightCommandIsConfirmedByDeviceBeforeStateChanges() throws Exception {
        JsonNode devices = json.readTree(mvc.perform(get("/api/devices").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        long lightId = 0;
        for (JsonNode d : devices) if (d.get("code").asText().equals("light-main")) lightId = d.get("id").asLong();
        assertTrue(lightId > 0);

        String res = mvc.perform(post("/api/devices/" + lightId + "/commands").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"power\":false}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        JsonNode cmd = json.readTree(res);
        assertEquals("REQUESTED", cmd.get("status").asText());

        String finalStatus = "";
        for (int i = 0; i < 40 && !finalStatus.equals("CONFIRMED"); i++) {
            Thread.sleep(100);
            finalStatus = json.readTree(mvc.perform(get("/api/commands/" + cmd.get("id").asText()).header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getContentAsString()).get("status").asText();
        }
        assertEquals("CONFIRMED", finalStatus);

        JsonNode light = json.readTree(mvc.perform(get("/api/devices/" + lightId).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString());
        assertEquals(false, light.get("state").get("power").asBoolean());
    }

    @Test
    void lightRejectsTemperatureCommand() throws Exception {
        JsonNode devices = json.readTree(mvc.perform(get("/api/devices").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString());
        long lightId = devices.get(0).get("code").asText().equals("light-main") ? devices.get(0).get("id").asLong() : devices.get(1).get("id").asLong();
        mvc.perform(post("/api/devices/" + lightId + "/commands").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"power\":true,\"targetTemp\":22}")).andExpect(status().isBadRequest());
    }

    @Test
    void overlappingReservationIsRejected() throws Exception {
        Instant start = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        String body = "{\"roomId\":1,\"title\":\"주간 회의\",\"startAt\":\"" + start + "\",\"endAt\":\"" + start.plus(1, ChronoUnit.HOURS) + "\"}";
        mvc.perform(post("/api/reservations").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        String overlap = "{\"roomId\":1,\"title\":\"겹침\",\"startAt\":\"" + start.plus(30, ChronoUnit.MINUTES) + "\",\"endAt\":\"" + start.plus(90, ChronoUnit.MINUTES) + "\"}";
        mvc.perform(post("/api/reservations").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(overlap))
                .andExpect(status().isConflict());
    }
}
