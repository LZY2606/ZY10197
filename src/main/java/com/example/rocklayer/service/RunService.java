package com.example.rocklayer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.example.rocklayer.repository.RunRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RunService {
    private final RunRepository runRepository;
    private final ScenarioService scenarioService;
    private final AgeModelService ageModelService;
    private final ObjectMapper mapper;

    public RunService(RunRepository runRepository, ScenarioService scenarioService,
                      AgeModelService ageModelService, ObjectMapper mapper) {
        this.runRepository = runRepository;
        this.scenarioService = scenarioService;
        this.ageModelService = ageModelService;
        this.mapper = mapper;
    }

    public JsonNode recompute(JsonNode requestScenario, boolean persistScenario) {
        JsonNode scenario = requestScenario == null || requestScenario.isNull() ? scenarioService.current() : requestScenario;
        if (persistScenario) scenarioService.replace(scenario);
        ObjectNode result = ageModelService.run(scenario);
        String id = UUID.randomUUID().toString();
        result.put("runId", id);
        result.put("createdAt", java.time.Instant.now().toString());
        result.set("requestScenario", scenario.deepCopy());
        String status = result.path("status").asText("OK");
        runRepository.save(id, status, scenario, result);
        return result;
    }

    public List<Map<String, Object>> listRuns() {
        return runRepository.list();
    }

    public JsonNode run(String id) {
        return runRepository.find(id);
    }
}
