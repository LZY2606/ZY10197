package com.drip.web;

import com.drip.data.StalagmiteRepository;
import com.drip.data.StalagmiteRepository.RunBundle;
import com.drip.data.StalagmiteRepository.RunSummary;
import com.drip.engine.AgeModelEngine;
import com.drip.model.DateSample;
import com.drip.model.HiatusSpec;
import com.drip.model.ModelConfig;
import com.drip.model.ModelResult;
import com.drip.model.ProxyPoint;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final StalagmiteRepository repo;
    private final ModelService service;

    public ApiController(StalagmiteRepository repo, ModelService service) {
        this.repo = repo;
        this.service = service;
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        return service.state();
    }

    // --------------------------------------------------------------- dates
    @PostMapping("/dates")
    public DateSample addDate(@RequestBody DateSample date) {
        validateDate(date);
        repo.upsertDate(date);
        return date;
    }

    @PostMapping("/dates/{id}/excluded")
    public Map<String, Object> setExcluded(@PathVariable String id, @RequestParam boolean excluded) {
        repo.setExcluded(id, excluded);
        return Map.of("id", id, "excluded", excluded);
    }

    @DeleteMapping("/dates/{id}")
    public Map<String, Object> deleteDate(@PathVariable String id) {
        repo.deleteDate(id);
        return Map.of("deleted", id);
    }

    private void validateDate(DateSample d) {
        if (d == null || d.id() == null || d.id().isBlank()) {
            throw new IllegalArgumentException("年代锦标 id 不能为空");
        }
        if (d.ageSigmaKa() <= 0) {
            throw new IllegalArgumentException("年龄不确定度必须为正");
        }
        if (d.sharedSigmaKa() < 0 || d.sharedSigmaKa() > d.ageSigmaKa() + 1e-9) {
            throw new IllegalArgumentException("共享校正分量必须介于 0 与总不确定度之间");
        }
    }

    // ------------------------------------------------------------- hiatuses
    @PostMapping("/hiatuses")
    public HiatusSpec addHiatus(@RequestBody HiatusSpec h) {
        if (h == null || h.id() == null || h.bottomDepthMm() <= h.topDepthMm()) {
            throw new IllegalArgumentException("间断需要 id 且底深必须大于顶深");
        }
        repo.upsertHiatus(h);
        return h;
    }

    @PutMapping("/hiatuses/{id}")
    public HiatusSpec updateHiatus(@PathVariable String id, @RequestBody HiatusSpec h) {
        HiatusSpec merged = new HiatusSpec(id, h.topDepthMm(), h.bottomDepthMm(),
                h.topOpen(), h.bottomOpen());
        if (merged.bottomDepthMm() <= merged.topDepthMm()) {
            throw new IllegalArgumentException("间断底深必须大于顶深");
        }
        repo.upsertHiatus(merged);
        return merged;
    }

    // ---------------------------------------------------------- run / query
    @PostMapping("/run")
    public ModelService.RunView run(@RequestBody(required = false) RunRequest req) {
        ModelConfig base = ModelConfig.defaults();
        ModelConfig cfg;
        String label = null;
        if (req != null) {
            int draws = req.draws() == null || req.draws() <= 0 ? base.draws() : req.draws();
            long seed = req.seed() == null ? base.seed() : req.seed();
            double grid = req.gridStepKa() == null || req.gridStepKa() <= 0 ? base.gridStepKa() : req.gridStepKa();
            double corr = req.corridorStepMm() == null || req.corridorStepMm() <= 0
                    ? base.corridorStepMm() : req.corridorStepMm();
            cfg = new ModelConfig(draws, seed, grid, corr);
            label = req.label();
        } else {
            cfg = base;
        }
        return service.runCurrent(cfg, label);
    }

    public record RunRequest(Integer draws, Long seed, Double gridStepKa,
                             Double corridorStepMm, String label) {}

    @GetMapping("/runs")
    public List<RunSummary> runs() {
        return repo.listRuns();
    }

    @GetMapping("/runs/{id}")
    public RunBundle run(@PathVariable long id) {
        RunBundle rb = repo.getRun(id);
        if (rb == null) {
            throw new IllegalArgumentException("运行记录不存在: " + id);
        }
        return rb;
    }

    @GetMapping(value = "/runs/{id}/export", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> exportRun(@PathVariable long id) {
        String json = service.toExportJson(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"dripcalc-run-" + id + ".json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    @PostMapping("/runs/import")
    public Map<String, Object> importRun(@RequestBody String body,
                                         @RequestParam(defaultValue = "false") boolean replaceData) {
        ModelService.ExportBundle bundle = service.parseExport(body);
        long id = service.importBundle(bundle, replaceData);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("importedRunId", id);
        out.put("verified", true);
        out.put("fingerprint", bundle.fingerprint());
        out.put("replaceData", replaceData);
        return out;
    }

    /** 不写库，只对导出包做重算复核，返回重算指纹与是否一致。 */
    @PostMapping("/runs/verify")
    public Map<String, Object> verify(@RequestBody String body) {
        ModelService.ExportBundle bundle = service.parseExport(body);
        ModelService.Verification v = service.verify(bundle);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("match", v.match());
        out.put("fingerprintFile", v.fingerprintFile());
        out.put("fingerprintNow", v.fingerprintNow());
        return out;
    }

    // ---------------------------------------------------- fixture / conflict
    @PostMapping("/fixtures/reset")
    public Map<String, Object> resetFixtures() {
        repo.clearAll();
        repo.loadFixtures();
        return Map.of("reset", true, "state", service.state());
    }

    @PostMapping("/data/clear")
    public Map<String, Object> clearData() {
        repo.clearAll();
        return Map.of("cleared", true);
    }

    /**
     * 同深度冲突演示：在指定深度（默认与 D2 相同的 250 mm）放入一枚年龄区间不相容的锦标，
     * 立即运行并返回结果；冲突锦标仅用于本次演示，不落库、不放大任何误差。
     */
    @PostMapping("/demo/conflict")
    public Map<String, Object> conflictDemo(@RequestBody(required = false) ConflictDemoRequest req) {
        double depth = req != null && req.depthMm() != null ? req.depthMm() : 250.0;
        double age = req != null && req.ageKa() != null ? req.ageKa() : 9.5;
        double sigma = req != null && req.ageSigmaKa() != null ? req.ageSigmaKa() : 0.22;
        String atId = req != null && req.atDateId() != null ? req.atDateId() : "D2";

        List<DateSample> dates = new ArrayList<>(repo.findAllDates());
        DateSample target = dates.stream().filter(d -> d.id().equals(atId)).findFirst()
                .or(() -> dates.stream().filter(d -> Math.abs(d.depthMm() - depth) < 0.05).findFirst())
                .orElseThrow(() -> new IllegalArgumentException("找不到同深度参照锦标"));
        final double conflictDepth = target.depthMm();
        DateSample conflicting = new DateSample("CONFLICT@" + target.id(), conflictDepth, age, sigma,
                0.0, null, false);
        dates.add(conflicting);

        ModelConfig cfg = req != null && req.draws() != null
                ? new ModelConfig(req.draws(), 20260921L, 0.5, 1.0) : ModelConfig.defaults();
        ModelResult result = AgeModelEngine.run(dates, repo.findAllHiatuses(),
                repo.findAllProxy(), cfg);
        return Map.of(
                "conflictSample", conflicting,
                "note", "冲突锦标未落库；各分支独立保留，conflicts 给出不相容证据，误差未被放大",
                "result", result);
    }

    public record ConflictDemoRequest(Double depthMm, Double ageKa, Double ageSigmaKa,
                                      String atDateId, Integer draws) {}

    // ------------------------------------------------------------ index page
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "service", "岩层滴算台");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
