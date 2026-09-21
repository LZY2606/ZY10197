package com.example.rocklayer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.example.rocklayer.repository.RunRepository;
import com.example.rocklayer.repository.ScenarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScenarioService {
    private final ScenarioRepository scenarioRepository;
    private final RunRepository runRepository;
    private final ObjectMapper objectMapper;

    public ScenarioService(ScenarioRepository scenarioRepository, RunRepository runRepository, ObjectMapper objectMapper) {
        this.scenarioRepository = scenarioRepository;
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
    }

    public JsonNode current() {
        JsonNode stored = scenarioRepository.get();
        if (stored != null) {
            return stored;
        }
        JsonNode fixture = defaultScenario();
        scenarioRepository.save(fixture);
        return fixture;
    }

    public JsonNode reset() {
        JsonNode fixture = defaultScenario();
        scenarioRepository.save(fixture);
        return fixture;
    }

    public JsonNode replace(JsonNode incoming) {
        JsonNode normalized = objectMapper.valueToTree(incoming);
        scenarioRepository.save(normalized);
        return normalized;
    }

    @Transactional
    public ObjectNode exportSnapshot() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("format", "rock-layer-chronology/v1");
        root.put("exportedAt", java.time.Instant.now().toString());
        root.set("scenario", current().deepCopy());
        ArrayNode runs = root.putArray("runs");
        for (var row : runRepository.allRows()) {
            ObjectNode item = runs.addObject();
            item.put("id", (String) row.get("id"));
            item.put("createdAt", (String) row.get("created_at"));
            item.put("status", (String) row.get("status"));
            try {
                item.set("request", objectMapper.readTree((String) row.get("request_json")));
                item.set("response", objectMapper.readTree((String) row.get("response_json")));
            } catch (Exception e) {
                throw new IllegalStateException("Unable to export stored run", e);
            }
        }
        return root;
    }

    @Transactional
    public ObjectNode clearDatabase() {
        runRepository.clear();
        scenarioRepository.clear();
        ObjectNode result = objectMapper.createObjectNode();
        result.put("cleared", true);
        return result;
    }

    @Transactional
    public ObjectNode importSnapshot(JsonNode snapshot, boolean resetBeforeImport) {
        if (!"rock-layer-chronology/v1".equals(text(snapshot, "format"))) {
            throw new IllegalArgumentException("Unsupported snapshot format");
        }
        JsonNode scenario = snapshot.get("scenario");
        if (scenario == null || !scenario.isObject()) {
            throw new IllegalArgumentException("Snapshot must contain a scenario object");
        }
        if (resetBeforeImport) {
            runRepository.clear();
            scenarioRepository.clear();
        }
        scenarioRepository.save(scenario.deepCopy());
        int importedRuns = 0;
        JsonNode runs = snapshot.get("runs");
        if (runs != null && runs.isArray()) {
            for (JsonNode run : runs) {
                String id = text(run, "id");
                String status = text(run, "status");
                JsonNode request = run.get("request");
                JsonNode response = run.get("response");
                if (id.isBlank() || request == null || response == null) {
                    throw new IllegalArgumentException("Each run must contain id, request and response");
                }
                runRepository.save(id, status.isBlank() ? "UNKNOWN" : status, request, response);
                importedRuns++;
            }
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.put("importedRuns", importedRuns);
        result.set("scenario", scenario.deepCopy());
        return result;
    }

    public JsonNode defaultScenario() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("fixtureVersion", "rock-layer-v1");
        root.put("name", "石笋示例剖面：两段生长与一个明确间断");
        root.put("depthUnit", "mm");
        root.put("ageUnit", "yr BP");
        root.put("proxyName", "δ18O");
        root.put("proxyUnit", "‰ VPDB");
        root.put("gridStart", 0);
        root.put("gridStop", 6500);
        root.put("gridStep", 250);
        root.put("branchCount", 160);
        root.put("sharedCorrectionSigma", 30);

        ObjectNode hiatus = root.putObject("hiatus");
        hiatus.put("youngDepth", 100);
        hiatus.put("oldDepth", 102);
        hiatus.put("youngSideOpen", false);
        hiatus.put("oldSideOpen", false);

        ArrayNode dates = root.putArray("dates");
        addDate(dates, "SURFACE", 0, 0, 0, null, true, "fixed");
        addDate(dates(dates), "U-040", 40, 800, 90, "G1", true, "u-th");
        addDate(dates, "U-160", 160, 3000, 120, "G1", true, "u-th");
        addDate(dates, "BASE", 200, 6200, 0, null, true, "fixed");

        ArrayNode proxies = root.putArray("proxies");
        double upper = 5;
        while (upper <= 60.001) {
            addProxy(proxies, Math.round(upper * 10.0) / 10.0, -5.6 - 0.012 * upper + Math.sin(upper / 7.0) * 0.25);
            upper += 5;
        }
        double lower = 140;
        while (lower <= 195.001) {
            addProxy(proxies, Math.round(lower * 10.0) / 10.0, -7.1 + 0.008 * (lower - 140) + Math.cos(lower / 11.0) * 0.22);
            lower += 5;
        }
        return root;
    }

    private ArrayNode dates(ArrayNode dates) {
        return dates;
    }

    private void addDate(ArrayNode dates, String id, double depth, double age, double sigma, String group, boolean active, String kind) {
        ObjectNode node = dates.addObject();
        node.put("id", id);
        node.put("depth", depth);
        node.put("ageBP", age);
        node.put("ageSigma", sigma);
        if (group == null) {
            node.putNull("correctionGroup");
        } else {
            node.put("correctionGroup", group);
        }
        node.put("active", active);
        node.put("kind", kind);
    }

    private void addProxy(ArrayNode proxies, double depth, double value) {
        ObjectNode node = proxies.addObject();
        node.put("depth", depth);
        node.put("value", Math.round(value * 10000.0) / 10000.0);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }
}
