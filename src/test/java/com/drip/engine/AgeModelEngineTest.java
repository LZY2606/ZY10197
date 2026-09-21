package com.drip.engine;

import com.drip.data.Fixtures;
import com.drip.model.DateSample;
import com.drip.model.HiatusSpec;
import com.drip.model.ModelConfig;
import com.drip.model.ModelResult;
import com.drip.model.ProxyPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgeModelEngineTest {

    private ModelConfig cfg() {
        return new ModelConfig(1500, 20260921L, 0.5, 1.0);
    }

    private ModelResult fixtureRun(List<HiatusSpec> hiatuses) {
        return AgeModelEngine.run(Fixtures.dates(), hiatuses, Fixtures.proxy(), cfg());
    }

    @Test
    void fixtureHasTwoSegmentsOneHiatusAndSingleBranch() {
        ModelResult r = fixtureRun(Fixtures.hiatuses());
        assertEquals(1, r.branches.size());
        ModelResult.Branch b = r.branches.get(0);
        assertTrue(b.conflicts().isEmpty());
        assertEquals(1, b.gaps().size());
        // 两个生长段各有一对相邻锦标 -> 两个局部生长率
        assertEquals(2, b.growthRates().size());
    }

    @Test
    void corridorDoesNotCrossHiatusAndMarksUnsupportedNodes() {
        ModelResult r = fixtureRun(Fixtures.hiatuses());
        ModelResult.Branch b = r.branches.get(0);

        // 间断 300-350 mm 内不得出现任何走廊节点
        for (ModelResult.CorridorPoint p : b.corridor()) {
            assertFalse(p.depthMm() > 300.0 && p.depthMm() < 350.0,
                    "走廊节点不得落在间断内部: " + p.depthMm());
        }
        // 300 与 350 边界节点为无支持缺失（最浅/深锦标到间断之间不外推连线）
        assertTrue(b.corridor().stream().anyMatch(p -> p.depthMm() == 300.0 && p.unsupported()));
        assertTrue(b.corridor().stream().anyMatch(p -> p.depthMm() == 350.0 && p.unsupported()));

        // 段内最深处（250）的年龄必须远浅于下一段最浅处（400）：两条线断开而非慢生长连接
        double ageAt250 = b.corridor().stream().filter(p -> p.depthMm() == 250.0)
                .findFirst().orElseThrow().q500();
        double ageAt400 = b.corridor().stream().filter(p -> p.depthMm() == 400.0)
                .findFirst().orElseThrow().q500();
        assertTrue(ageAt400 > ageAt250 + 5.0,
                "间断两侧年龄走廊必须断开，而不是缓慢生长插值");
    }

    @Test
    void adjustingHiatusEndpointsKeepsNoInterpolation() {
        // 验收：把间断扩大到 280–370 mm，仍不得跨间断插值
        List<HiatusSpec> moved = List.of(new HiatusSpec("H1", 280.0, 370.0, false, false));
        ModelResult r = fixtureRun(moved);
        ModelResult.Branch b = r.branches.get(0);
        for (ModelResult.CorridorPoint p : b.corridor()) {
            assertFalse(p.depthMm() > 280.0 && p.depthMm() < 370.0);
        }
        assertTrue(b.corridor().stream().anyMatch(p -> p.depthMm() == 280.0 && p.unsupported()));
        assertTrue(b.corridor().stream().anyMatch(p -> p.depthMm() == 370.0 && p.unsupported()));
    }

    @Test
    void gapProjectionAndNearestNeighbor() {
        ModelResult r = fixtureRun(Fixtures.hiatuses());
        ModelResult.GapStat g = r.branches.get(0).gaps().get(0);
        assertFalse(g.topOpen());
        assertFalse(g.bottomOpen());
        // 两侧闭合：投影间断与最近邻差值都可算，且为正
        assertNotNull(g.projectedQ500());
        assertNotNull(g.nearestNeighborQ500());
        assertTrue(g.projectedQ500() > 0);
        assertTrue(g.nearestNeighborQ500() > 6.0, "最近邻年龄差 12.8-6.5≈6.3 ka");
    }

    @Test
    void openBoundaryDropsProjectionButKeepsMinimumGap() {
        List<HiatusSpec> open = List.of(new HiatusSpec("H1", 300.0, 350.0, true, true));
        ModelResult r = fixtureRun(open);
        ModelResult.GapStat g = r.branches.get(0).gaps().get(0);
        assertNull(g.projectedQ500(), "两侧开放时不投影边界年龄");
        assertNotNull(g.nearestNeighborQ500(), "最近邻最小间断时长仍可报告");
    }

    @Test
    void proxyTimeGridKeepsMissingPointsOverGapWithCoverage() {
        ModelResult r = fixtureRun(Fixtures.hiatuses());
        ModelResult.Branch b = r.branches.get(0);
        List<ModelResult.ProxyGridPoint> grid = b.proxyGrid();
        assertFalse(grid.isEmpty());
        // 上一段支持年龄止于 ~6.5 ka，下一段始于 ~12.8 ka：之间的格点必须缺失且覆盖率为 0
        List<ModelResult.ProxyGridPoint> gapAges = grid.stream()
                .filter(p -> p.ageKa() >= 8.0 && p.ageKa() <= 11.5).toList();
        assertFalse(gapAges.isEmpty());
        for (ModelResult.ProxyGridPoint p : gapAges) {
            assertTrue(p.missing());
            assertEquals(0.0, p.coverage(), 1e-12);
        }
        // 段内格点覆盖率应接近 1
        assertTrue(grid.stream().filter(p -> p.ageKa() >= 3.0 && p.ageKa() <= 5.5)
                .allMatch(p -> !p.missing() && p.coverage() > 0.99));
    }

    @Test
    void sameDepthConflictReturnsEvidenceAndKeepsBranchesWithoutInflatingErrors() {
        DateSample conflict = new DateSample("CONFLICT@D2", 250.0, 9.5, 0.22, 0.0, null, false);
        List<DateSample> dates = new java.util.ArrayList<>(Fixtures.dates());
        dates.add(conflict);

        ModelResult r = AgeModelEngine.run(dates, Fixtures.hiatuses(), Fixtures.proxy(), cfg());
        assertEquals(2, r.branches.size(), "冲突锦标各自保留为独立分支，不平均成假曲线");

        // 每个分支只含同深度二选一锦标
        assertTrue(r.branches.stream().anyMatch(br -> br.dateIds().contains("D2")
                && !br.dateIds().contains("CONFLICT@D2")));
        assertTrue(r.branches.stream().anyMatch(br -> br.dateIds().contains("CONFLICT@D2")
                && !br.dateIds().contains("D2")));

        ModelResult.Conflict c = r.branches.get(0).conflicts().get(0);
        ModelResult.Conflict.PairEvidence pair = c.incompatiblePairs().get(0);
        assertTrue(pair.incompatible());
        assertTrue(Math.abs(pair.zScore()) > 2.0);
        // 关键：证据中保留的不确定度仍是原始 0.22，未被统一放大；σ差≈√2*0.22≈0.311
        assertEquals(Math.sqrt(2) * 0.22, pair.sigmaDiffKa(), 1e-9);
        assertEquals(0.22, c.members().stream()
                .filter(m -> m.dateId().equals("D2")).findFirst().orElseThrow().ageSigmaKa(), 1e-12);
        assertEquals(0.22, c.members().stream()
                .filter(m -> m.dateId().equals("CONFLICT@D2")).findFirst().orElseThrow().ageSigmaKa(), 1e-12);
    }

    @Test
    void sharedCorrelationGroupReducesPairwiseDifferenceSigma() {
        // 同深度、同相关组：σ差 = sqrt(σa²+σb²-2*shared_a*shared_b)
        DateSample a = new DateSample("X1", 100.0, 5.0, 0.30, 0.12, "G1", false);
        DateSample b = new DateSample("X2", 100.0, 5.1, 0.30, 0.12, "G1", false);
        DateSample c = new DateSample("X3", 100.0, 5.1, 0.30, 0.0, null, false);

        var clAB = AgeModelEngine.clusterByDepth(List.of(a, b)).get(0);
        var clAC = AgeModelEngine.clusterByDepth(List.of(a, c)).get(0);
        double sdAB = Math.sqrt(0.09 + 0.09 - 2 * 0.12 * 0.12);
        double sdAC = Math.sqrt(0.09 + 0.09);
        // 相容同深度组不产生冲突证据
        assertNull(AgeModelEngine.evaluateCluster(clAB));
        assertEquals(0.12 * 0.12, AgeModelEngine.sharedCovariance(a, b), 1e-12);
        assertEquals(0.0, AgeModelEngine.sharedCovariance(a, c), 1e-12);
        assertTrue(sdAB < sdAC, "共享校正参数使差不确定度减小（正协方差）");
    }

    @Test
    void isotonicRegressionEnforcesMonotonicAgesWithinSegment() {
        // 深度 100/200，名义年龄倒置 10 -> 5；PAVA 后必须非递减
        List<DateSample> dates = List.of(
                new DateSample("A", 100.0, 10.0, 0.05, 0.0, null, false),
                new DateSample("B", 200.0, 5.0, 0.05, 0.0, null, false),
                new DateSample("C", 300.0, 12.0, 0.05, 0.0, null, false));
        ModelResult r = AgeModelEngine.run(dates, List.of(), List.of(), cfg());
        ModelResult.Branch br = r.branches.get(0);
        Double prev = null;
        for (ModelResult.CorridorPoint p : br.corridor()) {
            if (p.unsupported() || Double.isNaN(p.q500())) continue;
            if (prev != null) {
                assertTrue(p.q500() >= prev - 1e-9, "段内年龄必须单调不减");
            }
            prev = p.q500();
        }
    }

    @Test
    void excludingADateIsRespected() {
        DateSample excluded = new DateSample("D2", 250.0, 6.5, 0.22, 0.10, "G1", true);
        List<DateSample> dates = new java.util.ArrayList<>(Fixtures.dates());
        dates.removeIf(d -> d.id().equals("D2"));
        dates.add(excluded);
        ModelResult r = AgeModelEngine.run(dates, Fixtures.hiatuses(), Fixtures.proxy(), cfg());
        assertEquals(3, r.meta.get("activeDateCount"));
        assertTrue(r.branches.get(0).dateIds().stream().noneMatch(id -> id.equals("D2")));
    }

    @Test
    void deterministicReplayWithSameSeed() {
        ModelResult r1 = fixtureRun(Fixtures.hiatuses());
        ModelResult r2 = fixtureRun(Fixtures.hiatuses());
        ModelResult r3 = AgeModelEngine.run(Fixtures.dates(), Fixtures.hiatuses(),
                Fixtures.proxy(), new ModelConfig(1500, 999L, 0.5, 1.0));
        // 同种子：分位数逐点一致
        for (int i = 0; i < r1.branches.get(0).corridor().size(); i++) {
            assertEquals(r1.branches.get(0).corridor().get(i).q500(),
                    r2.branches.get(0).corridor().get(i).q500(), 1e-12);
        }
        // 不同种子可以不同（极小概率相同，这里验证确定性 LCG 而非 Math.random：两次同种子恒等已足够）
        assertNotNull(r3);
    }

    @Test
    void growthRatesComeFromWithinSegmentOnly() {
        ModelResult r = fixtureRun(Fixtures.hiatuses());
        for (ModelResult.GrowthRatePoint gr : r.branches.get(0).growthRates()) {
            assertFalse(gr.depthMm() > 300 && gr.depthMm() < 350);
            assertTrue(gr.q500() > 0.02 && gr.q500() < 0.1);
        }
    }
}
