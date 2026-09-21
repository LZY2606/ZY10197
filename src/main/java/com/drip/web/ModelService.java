package com.drip.web;

import com.drip.data.StalagmiteRepository;
import com.drip.data.StalagmiteRepository.Snapshot;
import com.drip.engine.AgeModelEngine;
import com.drip.model.DateSample;
import com.drip.model.HiatusSpec;
import com.drip.model.ModelConfig;
import com.drip.model.ModelResult;
import com.drip.model.ProxyPoint;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ModelService {

    private final StalagmiteRepository repo;
    private final ObjectMapper mapper;

    public ModelService(StalagmiteRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    public RunView runCurrent(ModelConfig cfg, String label) {
        List<DateSample> dates = repo.findAllDates();
        List<HiatusSpec> hiatuses = repo.findAllHiatuses();
        List<ProxyPoint> proxy = repo.findAllProxy();
        ModelResult result = AgeModelEngine.run(dates, hiatuses, proxy, cfg);
        Snapshot snapshot = new Snapshot(dates, hiatuses, proxy);
        String fingerprint = fingerprint(result);
        long id = repo.saveRun(label, fingerprint, cfg, writeJson(snapshot), result);
        return new RunView(id, fingerprint, result);
    }

    /** 复核导出记录：用快照重算并与导出的指纹比对。 */
    public Verification verify(ExportBundle bundle) {
        ModelConfig cfg = bundle.config() != null ? bundle.config() : ModelConfig.defaults();
        Snapshot snap = bundle.snapshot();
        if (snap == null || snap.dates() == null) {
            throw new IllegalArgumentException("导出包缺少 snapshot 数据");
        }
        ModelResult recomputed = AgeModelEngine.run(snap.dates(),
                snap.hiatuses() == null ? List.of() : snap.hiatuses(),
                snap.proxy() == null ? List.of() : snap.proxy(), cfg);
        String fpNow = fingerprint(recomputed);
        String fpFile = bundle.fingerprint();
        boolean match = fpNow.equals(fpFile);
        return new Verification(match, fpFile, fpNow, recomputed);
    }

    public String fingerprint(ModelResult result) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(mapper.writeValueAsBytes(result));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("指纹计算失败", e);
        }
    }

    public ExportBundle toExportBundle(long runId) {
        StalagmiteRepository.RunBundle rb = repo.getRun(runId);
        if (rb == null) {
            throw new IllegalArgumentException("运行记录不存在: " + runId);
        }
        return new ExportBundle(rb.label(), rb.fingerprint(), rb.config(), rb.snapshot(), rb.result());
    }

    public String toExportJson(long runId) {
        return writeJson(toExportBundle(runId));
    }

    public long importBundle(ExportBundle bundle, boolean replaceData) {
        Verification verification = verify(bundle);
        if (!verification.match()) {
            throw new IllegalArgumentException(
                    "复核失败：导出指纹 " + bundle.fingerprint() + " 与重算指纹 " + verification.fingerprintNow()
                            + " 不一致，拒绝导入");
        }
        if (replaceData) {
            repo.clearAll();
            for (DateSample d : bundle.snapshot().dates()) {
                repo.upsertDate(d);
            }
            for (HiatusSpec h : bundle.snapshot().hiatuses()) {
                repo.upsertHiatus(h);
            }
            repo.replaceProxy(bundle.snapshot().proxy());
        }
        return repo.saveRun(bundle.label(), bundle.fingerprint(), bundle.config(),
                writeJson(bundle.snapshot()), verification.recomputed());
    }

    public ExportBundle parseExport(String json) {
        try {
            return mapper.readValue(json, ExportBundle.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析导出包: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> state() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("dates", repo.findAllDates());
        state.put("hiatuses", repo.findAllHiatuses());
        state.put("proxyCount", repo.findAllProxy().size());
        return state;
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record RunView(long id, String fingerprint, ModelResult result) {}

    public record Verification(boolean match, String fingerprintFile,
                               String fingerprintNow, ModelResult recomputed) {}

    public record ExportBundle(String label, String fingerprint, ModelConfig config,
                               Snapshot snapshot, ModelResult result) {}

}
