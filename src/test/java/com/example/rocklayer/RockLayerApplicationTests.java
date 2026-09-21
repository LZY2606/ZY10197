package com.example.rocklayer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/rocklayer-test.db",
        "server.port=0"
})
@AutoConfigureMockMvc
class RockLayerApplicationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void showsChineseTitle() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));
        String html = mvc.perform(get("/index.html")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("岩层滴算台");
    }

    @Test
    void buildsTwoSegmentsAndKeepsUnsupportedGridMissing() throws Exception {
        JsonNode result = run(defaultScenario());
        assertThat(result.path("status").asText()).isEqualTo("OK");
        assertThat(result.path("segments")).hasSize(2);
        assertThat(result.path("hiatusDuration").path("estimable").asBoolean()).isTrue();

        long missingRows = result.path("proxyGrid").findValuesAsText("supported").stream()
                .filter("false"::equals).count();
        assertThat(missingRows).isGreaterThan(0);
        assertThat(result.path("proxyGrid")).anyMatch(row -> row.path("mean").isNull());
        assertThat(result.path("branchSeries")).hasSize(160);
        assertThat(result.path("branchSeries").get(0).path("segments")).hasSize(2);
    }

    @Test
    void openHiatusSidesDoNotEstimateDurationAndDoNotInterpolateAcrossGap() throws Exception {
        ObjectNode scenario = defaultScenario();
        scenario.with("hiatus").put("youngSideOpen", true);
        scenario.with("hiatus").put("oldSideOpen", true);
        JsonNode result = run(scenario);
        assertThat(result.path("hiatusDuration").path("estimable").asBoolean()).isFalse();
        assertThat(result.path("hiatusDuration").path("q025").isNull()).isTrue();
        assertThat(result.path("ageCorridor")).isNotEmpty();
        for (JsonNode row : result.path("ageCorridor")) {
            double depth = row.path("depth").asDouble();
            assertThat(depth).satisfiesAnyOf(d -> assertThat(d).isLessThanOrEqualTo(100.0),
                    d -> assertThat(d).isGreaterThanOrEqualTo(102.0));
        }
    }

    @Test
    void excludesOneDateAndKeepsModelSegmented() throws Exception {
        ObjectNode scenario = defaultScenario();
        for (JsonNode date : scenario.path("dates")) {
            if (date.path("id").asText().equals("U-160")) ((ObjectNode) date).put("active", false);
        }
        JsonNode result = run(scenario);
        assertThat(result.path("status").asText()).isEqualTo("OK");
        assertThat(result.path("segments")).hasSize(2);
    }

    @Test
    void sameDepthConflictReturnsEvidenceWithoutInflatingSigmas() throws Exception {
        ObjectNode scenario = defaultScenario();
        ObjectNode conflict = mapper.createObjectNode();
        conflict.put("id", "U-CONFLICT").put("depth", 40).put("ageBP", 5000).put("ageSigma", 100)
                .put("correctionGroup", "OTHER").put("active", true).put("kind", "u-th");
        scenario.withArray("dates").add(conflict);
        JsonNode result = run(scenario);
        assertThat(result.path("status").asText()).isEqualTo("CONFLICT");
        assertThat(result.path("proxyGrid").isMissingNode()).isTrue();
        JsonNode evidence = result.path("conflicts").get(0);
        assertThat(evidence.path("type").asText()).isEqualTo("SAME_DEPTH_AGE_INCOMPATIBLE");
        assertThat(evidence.path("dateA").asText()).isEqualTo("U-040");
        assertThat(evidence.path("dateB").asText()).isEqualTo("U-CONFLICT");
        assertThat(evidence.path("z").asDouble()).isGreaterThan(3);
        assertThat(evidence.path("errorInflationApplied").asBoolean()).isFalse();
        for (JsonNode date : scenario.path("dates")) {
            if (date.path("id").asText().equals("U-040")) assertThat(date.path("ageSigma").asDouble()).isEqualTo(90);
            if (date.path("id").asText().equals("U-CONFLICT")) assertThat(date.path("ageSigma").asDouble()).isEqualTo(100);
        }
    }

    @Test
    void movingHiatusAcrossADateIsRejectedInsteadOfCrossInterpolated() throws Exception {
        ObjectNode scenario = defaultScenario();
        scenario.with("hiatus").put("youngDepth", 30).put("oldDepth", 50);
        mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(mapper.createObjectNode().set("scenario", scenario))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("年代控制点落入间断内部")));
    }

    @Test
    void exportClearImportReplaysStoredRuns() throws Exception {
        run(defaultScenario());
        JsonNode snapshot = mapper.readTree(mvc.perform(get("/api/export")).andReturn().getResponse().getContentAsString());
        assertThat(snapshot.path("runs")).isNotEmpty();

        mvc.perform(post("/api/admin/clear")).andExpect(status().isOk());
        mvc.perform(get("/api/runs")).andExpect(jsonPath("$").isArray()).andExpect(jsonPath("$.length()").value(0));

        mvc.perform(post("/api/import?resetBeforeImport=true").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(snapshot)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedRuns").value(snapshot.path("runs").size()));
        String id = snapshot.path("runs").get(0).path("id").asText();
        mvc.perform(get("/api/runs/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.runId").value(id));
    }

    private ObjectNode defaultScenario() throws Exception {
        String body = mvc.perform(post("/api/scenario/reset")).andReturn().getResponse().getContentAsString();
        return (ObjectNode) mapper.readTree(body);
    }

    private JsonNode run(ObjectNode scenario) throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.set("scenario", scenario);
        request.put("persistScenario", false);
        String body = mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request))).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }
}
