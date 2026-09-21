package com.example.rocklayer.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.example.rocklayer.service.RunService;
import com.example.rocklayer.service.ScenarioService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final ScenarioService scenarioService;
    private final RunService runService;

    public ApiController(ScenarioService scenarioService, RunService runService) {
        this.scenarioService = scenarioService;
        this.runService = runService;
    }

    @GetMapping("/scenario")
    public JsonNode scenario() {
        return scenarioService.current();
    }

    @PutMapping("/scenario")
    public JsonNode replaceScenario(@RequestBody JsonNode scenario) {
        return scenarioService.replace(scenario);
    }

    @PostMapping("/scenario/reset")
    public JsonNode reset() {
        return scenarioService.reset();
    }

    @PostMapping("/runs")
    public JsonNode run(@RequestBody(required = false) JsonNode body) {
        JsonNode scenario = body == null ? null : body.get("scenario");
        boolean persist = body == null || !body.has("persistScenario") || body.get("persistScenario").asBoolean(true);
        return runService.recompute(scenario, persist);
    }

    @GetMapping("/runs")
    public java.util.List<Map<String, Object>> runs() {
        return runService.listRuns();
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<JsonNode> run(@PathVariable String id) {
        JsonNode run = runService.run(id);
        return run == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(run);
    }

    @PostMapping("/admin/clear")
    public JsonNode clear() {
        return scenarioService.clearDatabase();
    }

    @PostMapping("/admin/reseed")
    public JsonNode reseed() {
        scenarioService.clearDatabase();
        return scenarioService.reset();
    }

    @GetMapping("/export")
    public JsonNode exportSnapshot() {
        return scenarioService.exportSnapshot();
    }

    @PostMapping("/import")
    public JsonNode importSnapshot(@RequestBody JsonNode snapshot,
                                   @RequestParam(defaultValue = "true") boolean resetBeforeImport) {
        return scenarioService.importSnapshot(snapshot, resetBeforeImport);
    }
}
