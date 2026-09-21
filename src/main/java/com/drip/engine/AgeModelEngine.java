package com.drip.engine;

import com.drip.model.DateSample;
import com.drip.model.HiatusSpec;
import com.drip.model.ModelConfig;
import com.drip.model.ModelResult;
import com.drip.model.ProxyPoint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 石笋单调年代模型引擎。
 *
 * <p>关键口径：
 * <ul>
 *   <li>深度自顶向下增大，年龄单调增大；每个生长段内部独立建模，间断两侧绝不线性插值。</li>
 *   <li>同相关组共享碎屑校正参数，抽样年龄 a_i = μ_i + shared_i·g_group + indep_i·ε_i。</li>
 *   <li>每个生长段内部用加权 PAVA（isotonic）处理抽样年龄，拒绝为“平滑曲线”抹平冲突。</li>
 *   <li>同一深度的冲突锦标各自保留为独立分支，并返回不相容证据，不统一放大任何误差。</li>
 *   <li>开放边界不向外投影年龄；落在无年代支持区域的格点保留为缺失并给覆盖率。</li>
 * </ul>
 */
public final class AgeModelEngine {

    private static final double DEPTH_TOL_MM = 0.05;
    private static final double CONFLICT_Z = 2.0;

    private AgeModelEngine() {}

    public static ModelResult run(List<DateSample> allDates, List<HiatusSpec> hiatuses,
                                  List<ProxyPoint> proxy, ModelConfig cfg) {
        ModelResult result = new ModelResult();
        result.config = cfg;

        List<String> warnings = new ArrayList<>();
        List<DateSample> active = new ArrayList<>();
        for (DateSample d : allDates) {
            if (d.excluded()) {
                continue;
            }
            boolean insideHiatus = false;
            for (HiatusSpec h : hiatuses) {
                if (d.depthMm() > h.topDepthMm() + 1e-9 && d.depthMm() < h.bottomDepthMm() - 1e-9) {
                    insideHiatus = true;
                }
            }
            if (insideHiatus) {
                warnings.add("date " + d.id() + " 位于某间断深度区间内部，已忽略");
            } else {
                active.add(d);
            }
        }
        active.sort((a, b) -> Double.compare(a.depthMm(), b.depthMm()));

        List<DepthCluster> clusters = clusterByDepth(active);
        List<ModelResult.Conflict> globalConflicts = new ArrayList<>();
        int branchCount = 1;
        for (DepthCluster c : clusters) {
            ModelResult.Conflict conflict = evaluateCluster(c);
            if (conflict != null) {
                globalConflicts.add(conflict);
            }
            if (c.members.size() > 1) {
                branchCount *= c.members.size();
            }
        }

        List<List<DateSample>> branchPicks = enumerateBranches(clusters);
        List<Segment> segments = buildSegments(hiatuses, active);

        List<ModelResult.Branch> branches = new ArrayList<>();
        for (int bi = 0; bi < branchPicks.size(); bi++) {
            List<DateSample> picks = branchPicks.get(bi);
            String key = branchKey(picks);
            String label = "分支 " + (bi + 1);
            if (branchPicks.size() == 1) {
                label = "主分支";
            }
            BranchWorkspace ws = simulate(picks, segments, hiatuses, proxy, cfg, label, key, globalConflicts);
            branches.add(ws.branch);
        }

        result.branches = branches;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("activeDateCount", active.size());
        meta.put("branchCount", branches.size());
        meta.put("hiatusCount", hiatuses.size());
        meta.put("segmentCount", segments.size());
        meta.put("conflictCount", globalConflicts.size());
        meta.put("warnings", warnings);
        result.meta = meta;
        return result;
    }

    // ------------------------------------------------------------------
    // 同深度分组、冲突证据、分支枚举
    // ------------------------------------------------------------------

    static final class DepthCluster {
        double depthMm;
        final List<DateSample> members = new ArrayList<>();
    }

    static List<DepthCluster> clusterByDepth(List<DateSample> sorted) {
        List<DepthCluster> out = new ArrayList<>();
        for (DateSample d : sorted) {
            DepthCluster last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && Math.abs(last.depthMm - d.depthMm()) <= DEPTH_TOL_MM) {
                last.members.add(d);
            } else {
                DepthCluster c = new DepthCluster();
                c.depthMm = d.depthMm();
                c.members.add(d);
                out.add(c);
            }
        }
        return out;
    }

    static ModelResult.Conflict evaluateCluster(DepthCluster c) {
        if (c.members.size() < 2) {
            return null;
        }
        List<ModelResult.Conflict.Member> members = new ArrayList<>();
        List<ModelResult.Conflict.PairEvidence> pairs = new ArrayList<>();
        double minAbs = Double.POSITIVE_INFINITY;
        boolean any = false;
        for (DateSample d : c.members) {
            members.add(new ModelResult.Conflict.Member(d.id(), d.depthMm(), d.ageKa(),
                    d.ageSigmaKa(), d.corrGroup()));
        }
        for (int i = 0; i < c.members.size(); i++) {
            for (int j = i + 1; j < c.members.size(); j++) {
                DateSample a = c.members.get(i);
                DateSample b = c.members.get(j);
                double cov = sharedCovariance(a, b);
                double varDiff = a.ageSigmaKa() * a.ageSigmaKa()
                        + b.ageSigmaKa() * b.ageSigmaKa() - 2.0 * cov;
                double sd = Math.sqrt(Math.max(varDiff, 0.0));
                double diff = b.ageKa() - a.ageKa();
                boolean bad = sd > 0 && Math.abs(diff) > CONFLICT_Z * sd;
                pairs.add(new ModelResult.Conflict.PairEvidence(a.id(), b.id(), a.ageKa(), b.ageKa(),
                        diff, sd, sd > 0 ? diff / sd : Double.NaN, bad));
                any |= bad;
                minAbs = Math.min(minAbs, Math.abs(diff));
            }
        }
        if (!any) {
            return null;
        }
        return new ModelResult.Conflict(fmt(c.depthMm), members, minAbs, pairs);
    }

    static double sharedCovariance(DateSample a, DateSample b) {
        if (a.corrGroup() == null || b.corrGroup() == null) {
            return 0.0;
        }
        if (!a.corrGroup().equals(b.corrGroup())) {
            return 0.0;
        }
        return a.sharedSigmaKa() * b.sharedSigmaKa();
    }

    static List<List<DateSample>> enumerateBranches(List<DepthCluster> clusters) {
        List<List<DateSample>> combos = new ArrayList<>();
        combos.add(new ArrayList<>());
        for (DepthCluster c : clusters) {
            List<List<DateSample>> next = new ArrayList<>();
            for (List<DateSample> base : combos) {
                for (DateSample pick : c.members) {
                    List<DateSample> ext = new ArrayList<>(base);
                    ext.add(pick);
                    next.add(ext);
                }
            }
            combos = next;
        }
        return combos;
    }

    private static String branchKey(List<DateSample> picks) {
        StringBuilder sb = new StringBuilder();
        for (DateSample d : picks) {
            sb.append(d.id()).append('|');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 生长段
    // ------------------------------------------------------------------

    static final class Segment {
        String id;
        double topDepth;   // 段顶深度（浅）
        double bottomDepth;// 段底深度（深）
        double minDepth;   // 有年代支持的最浅深度
        double maxDepth;   // 有年代支持的最深深度
        int count;
    }

    static List<Segment> buildSegments(List<HiatusSpec> hiatuses, List<DateSample> active) {
        List<HiatusSpec> sortedH = new ArrayList<>(hiatuses);
        sortedH.sort((a, b) -> Double.compare(a.topDepthMm(), b.topDepthMm()));
        double overallMin = Double.POSITIVE_INFINITY;
        double overallMax = Double.NEGATIVE_INFINITY;
        for (DateSample d : active) {
            overallMin = Math.min(overallMin, d.depthMm());
            overallMax = Math.max(overallMax, d.depthMm());
        }
        List<Segment> segments = new ArrayList<>();
        double cursor = overallMin;
        int idx = 1;
        for (HiatusSpec h : sortedH) {
            if (h.topDepthMm() > cursor) {
                Segment s = new Segment();
                s.id = "S" + idx++;
                s.topDepth = cursor;
                s.bottomDepth = h.topDepthMm();
                segments.add(s);
            }
            cursor = h.bottomDepthMm();
        }
        if (cursor <= overallMax || segments.isEmpty()) {
            Segment s = new Segment();
            s.id = "S" + idx;
            s.topDepth = cursor;
            s.bottomDepth = overallMax;
            segments.add(s);
        }
        return segments;
    }

    static String segmentAtDepth(List<Segment> segments, double depth) {
        for (Segment s : segments) {
            if (depth >= s.topDepth - 1e-9 && depth <= s.bottomDepth + 1e-9) {
                return s.id;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 单次分支的 Monte Carlo 模拟
    // ------------------------------------------------------------------

    static final class BranchWorkspace {
        ModelResult.Branch branch;
        BranchWorkspace(ModelResult.Branch b) { this.branch = b; }
    }

    static BranchWorkspace simulate(List<DateSample> picks, List<Segment> segments,
                                    List<HiatusSpec> hiatuses, List<ProxyPoint> proxy,
                                    ModelConfig cfg, String label, String key,
                                    List<ModelResult.Conflict> conflicts) {
        int draws = cfg.draws();

        Map<String, List<DateSample>> bySeg = new LinkedHashMap<>();
        for (Segment s : segments) {
            bySeg.put(s.id, new ArrayList<>());
        }
        for (DateSample d : picks) {
            String sid = segmentAtDepth(segments, d.depthMm());
            if (sid != null) {
                bySeg.get(sid).add(d);
            }
        }
        for (Segment s : segments) {
            List<DateSample> ds = bySeg.get(s.id);
            ds.sort((a, b) -> Double.compare(a.depthMm(), b.depthMm()));
            if (!ds.isEmpty()) {
                s.minDepth = ds.get(0).depthMm();
                s.maxDepth = ds.get(ds.size() - 1).depthMm();
                s.count = ds.size();
            }
        }

        // 深度走廊网格：段内节点 + 间断边界标记
        List<Double> corridorDepths = new ArrayList<>();
        List<String> corridorSeg = new ArrayList<>();
        List<Boolean> corridorUnsupported = new ArrayList<>();
        for (Segment s : segments) {
            if (s.count == 0) {
                continue;
            }
            // 开放边界（最浅锦标到段顶）与最深锦标到段底：保留为无年代支持节点
            double topNode = s.topDepth;
            boolean topUn = topNode < s.minDepth - 1e-9;
            appendNode(corridorDepths, corridorSeg, corridorUnsupported, topNode, s.id, topUn);
            // 支持区间 [minDepth, maxDepth] 内按步长取点，首尾锦标深度必含
            double z = s.minDepth;
            while (z < s.maxDepth - 1e-9) {
                appendNode(corridorDepths, corridorSeg, corridorUnsupported, z, s.id, false);
                z += cfg.corridorStepMm();
            }
            appendNode(corridorDepths, corridorSeg, corridorUnsupported, s.maxDepth, s.id, false);
            boolean botUn = s.bottomDepth > s.maxDepth + 1e-9;
            appendNode(corridorDepths, corridorSeg, corridorUnsupported, s.bottomDepth, s.id, botUn);
        }
        int nCorridor = corridorDepths.size();
        double[][] corridorSamples = new double[nCorridor][draws];
        boolean[] corridorValid = new boolean[nCorridor];

        // 生长率：段内相邻锦标之间
        List<String> rateSeg = new ArrayList<>();
        List<Double> rateDepth = new ArrayList<>();
        List<double[]> ratePair = new ArrayList<>();
        for (Segment s : segments) {
            List<DateSample> ds = bySeg.get(s.id);
            for (int k = 0; k + 1 < ds.size(); k++) {
                rateSeg.add(s.id);
                rateDepth.add((ds.get(k).depthMm() + ds.get(k + 1).depthMm()) / 2.0);
                ratePair.add(new double[]{ds.get(k).depthMm(), ds.get(k + 1).depthMm()});
            }
        }
        int nRate = rateSeg.size();
        double[][] rateSamples = new double[nRate][draws];

        // 间断统计
        int nGap = hiatuses.size();
        double[][] gapProj = new double[nGap][draws];
        double[][] gapNear = new double[nGap][draws];

        // 先确定该分支时间网格范围（所有有效抽样支持区间的并集包络）
        double ageLo = Double.POSITIVE_INFINITY;
        double ageHi = Double.NEGATIVE_INFINITY;


        List<HiatusSpec> sortedH = new ArrayList<>(hiatuses);
        sortedH.sort((a, b) -> Double.compare(a.topDepthMm(), b.topDepthMm()));

        DeterministicRng rng = new DeterministicRng(cfg.seed());
        List<Map<String, double[]>> drawSegAges = new ArrayList<>();
        List<Map<String, double[]>> drawSegDepths = new ArrayList<>();

        for (int draw = 0; draw < draws; draw++) {
            Map<String, Double> groupShifts = new LinkedHashMap<>();
            Map<String, double[]> segAges = new LinkedHashMap<>();
            Map<String, double[]> segDepths = new LinkedHashMap<>();

            for (Map.Entry<String, List<DateSample>> e : bySeg.entrySet()) {
                List<DateSample> ds = e.getValue();
                int n = ds.size();
                double[] depths = new double[n];
                double[] ages = new double[n];
                double[] weights = new double[n];
                for (int k = 0; k < n; k++) {
                    DateSample d = ds.get(k);
                    double shared = 0.0;
                    if (d.corrGroup() != null) {
                        Double g = groupShifts.get(d.corrGroup());
                        if (g == null) {
                            g = rng.nextGaussian();
                            groupShifts.put(d.corrGroup(), g);
                        }
                        shared = d.sharedSigmaKa() * g;
                    }
                    double idpVar = Math.max(d.ageSigmaKa() * d.ageSigmaKa()
                            - d.sharedSigmaKa() * d.sharedSigmaKa(), 0.0);
                    ages[k] = d.ageKa() + shared + Math.sqrt(idpVar) * rng.nextGaussian();
                    depths[k] = d.depthMm();
                    weights[k] = 1.0 / Math.max(d.ageSigmaKa() * d.ageSigmaKa(), 1e-12);
                }
                pavNonDecreasing(ages, weights);
                segAges.put(e.getKey(), ages);
                segDepths.put(e.getKey(), depths);
                for (int k = 0; k < n; k++) {
                    ageLo = Math.min(ageLo, ages[k]);
                    ageHi = Math.max(ageHi, ages[k]);
                }
            }

            // 年龄走廊：仅在段内 [minDepth,maxDepth] 之间插值；开放无支持区域与间断保留缺失
            for (int ci = 0; ci < nCorridor; ci++) {
                corridorSamples[ci][draw] = Double.NaN;
                if (corridorUnsupported.get(ci)) {
                    continue;
                }
                String sid = corridorSeg.get(ci);
                double[] depths = segDepths.get(sid);
                double[] ages = segAges.get(sid);
                if (depths == null || depths.length == 0) {
                    continue;
                }
                double z = corridorDepths.get(ci);
                if (z < depths[0] - 1e-9 || z > depths[depths.length - 1] + 1e-9) {
                    continue;
                }
                corridorSamples[ci][draw] = interpDepth(depths, ages, z);
                corridorValid[ci] = true;
            }

            // 局部生长率 mm/yr，仅由同段相邻锦标得到
            for (int ri = 0; ri < nRate; ri++) {
                String sid = rateSeg.get(ri);
                double[] depths = segDepths.get(sid);
                double[] ages = segAges.get(sid);
                double zA = ratePair.get(ri)[0];
                double zB = ratePair.get(ri)[1];
                double aA = interpDepth(depths, ages, zA);
                double aB = interpDepth(depths, ages, zB);
                double ageDiffKa = aB - aA;
                rateSamples[ri][draw] = ageDiffKa > 1e-9
                        ? (zB - zA) / (ageDiffKa * 1000.0) : Double.NaN;
            }

            // 间断时长：闭合边界做段内线性拟合投影；最近邻差值恒可算（最小间断时长）
            for (int gi = 0; gi < nGap; gi++) {
                HiatusSpec h = sortedH.get(gi);
                String upId = segmentAtDepth(segments, h.topDepthMm() - 1e-6);
                String loId = segmentAtDepth(segments, h.bottomDepthMm() + 1e-6);
                double[] upD = upId == null ? null : segDepths.get(upId);
                double[] upA = upId == null ? null : segAges.get(upId);
                double[] loD = loId == null ? null : segDepths.get(loId);
                double[] loA = loId == null ? null : segAges.get(loId);

                double topEdge = Double.NaN;
                double bottomEdge = Double.NaN;
                if (!h.topOpen() && upD != null && upD.length >= 2) {
                    topEdge = fitEdge(upD, upA, h.topDepthMm());
                }
                if (!h.bottomOpen() && loD != null && loD.length >= 2) {
                    bottomEdge = fitEdge(loD, loA, h.bottomDepthMm());
                }
                gapProj[gi][draw] = (!Double.isNaN(topEdge) && !Double.isNaN(bottomEdge))
                        ? bottomEdge - topEdge : Double.NaN;

                Double nearTop = (upA != null && upA.length > 0) ? upA[upA.length - 1] : null;
                Double nearBottom = (loA != null && loA.length > 0) ? loA[0] : null;
                gapNear[gi][draw] = (nearTop != null && nearBottom != null)
                        ? nearBottom - nearTop : Double.NaN;
            }
            drawSegAges.add(segAges);
            drawSegDepths.add(segDepths);
        }

        // 组装年龄走廊
        List<ModelResult.CorridorPoint> corridorOut = new ArrayList<>();
        for (int ci = 0; ci < nCorridor; ci++) {
            double[] vals = clean(corridorSamples[ci]);
            if (corridorUnsupported.get(ci) || vals.length == 0) {
                corridorOut.add(new ModelResult.CorridorPoint(round(corridorDepths.get(ci)),
                        Double.NaN, Double.NaN, Double.NaN,
                        vals.length / (double) draws, true, corridorSeg.get(ci)));
            } else {
                double[] qs = Percentiles.quantiles(vals, 0.025, 0.5, 0.975);
                corridorOut.add(new ModelResult.CorridorPoint(round(corridorDepths.get(ci)),
                        qs[0], qs[1], qs[2], vals.length / (double) draws, false, corridorSeg.get(ci)));
            }
        }

        // 组装生长率
        List<ModelResult.GrowthRatePoint> rateOut = new ArrayList<>();
        for (int ri = 0; ri < nRate; ri++) {
            double[] vals = clean(rateSamples[ri]);
            double[] qs = Percentiles.quantiles(vals, 0.025, 0.5, 0.975);
            rateOut.add(new ModelResult.GrowthRatePoint(rateSeg.get(ri), round(rateDepth.get(ri)),
                    qs[0], qs[1], qs[2], vals.length / (double) draws));
        }

        // 组装间断时长
        List<ModelResult.GapStat> gapOut = new ArrayList<>();
        for (int gi = 0; gi < nGap; gi++) {
            HiatusSpec h = sortedH.get(gi);
            double[] p = clean(gapProj[gi]);
            double[] nn = clean(gapNear[gi]);
            Double pq025 = null, pq500 = null, pq975 = null;
            Double nq025 = null, nq500 = null, nq975 = null;
            if (p.length > 0) {
                double[] qs = Percentiles.quantiles(p, 0.025, 0.5, 0.975);
                pq025 = qs[0]; pq500 = qs[1]; pq975 = qs[2];
            }
            if (nn.length > 0) {
                double[] qs = Percentiles.quantiles(nn, 0.025, 0.5, 0.975);
                nq025 = qs[0]; nq500 = qs[1]; nq975 = qs[2];
            }
            gapOut.add(new ModelResult.GapStat(h.id(), h.topDepthMm(), h.bottomDepthMm(),
                    h.topOpen(), h.bottomOpen(), pq025, pq500, pq975, nq025, nq500, nq975,
                    Math.max(p.length, nn.length) / (double) draws, gapInterpretation(h)));
        }

        // 规则时间网格上的代理指标统计
        List<ModelResult.ProxyGridPoint> proxyOut = buildProxyGrid(
                proxy, new DrawLookup(drawSegAges, drawSegDepths, bySeg),
                cfg, draws, ageLo, ageHi);

        ModelResult.Branch branch = new ModelResult.Branch(key, label,
                picks.stream().map(DateSample::id).toList(),
                conflicts, corridorOut, rateOut, gapOut, proxyOut);
        return new BranchWorkspace(branch);
    }

    private static String gapInterpretation(HiatusSpec h) {
        if (h.topOpen() && h.bottomOpen()) {
            return "两侧开放：不投影边界年龄，仅报告最近邻锦标最小间断时长（投影值缺失）";
        }
        if (h.topOpen() || h.bottomOpen()) {
            return "单侧开放：开放侧不投影，闭合投影缺失；最近邻差值为最小间断时长";
        }
        return "两侧闭合：投影间断时长=下侧投影年龄-上侧投影年龄；最近邻差值为保守最小间断时长";
    }

    private static void appendNode(List<Double> depths, List<String> segs,
                                   List<Boolean> unsupported, double depth, String seg, boolean un) {
        if (!depths.isEmpty() && Math.abs(depths.get(depths.size() - 1) - depth) < 1e-9) {
            return;
        }
        depths.add(depth);
        segs.add(seg);
        unsupported.add(un);
    }

    private static HiatusSpec sortedById(List<HiatusSpec> hiatuses, int gi) {
        List<HiatusSpec> s = new ArrayList<>(hiatuses);
        s.sort((a, b) -> Double.compare(a.topDepthMm(), b.topDepthMm()));
        return s.get(gi);
    }

    // ------------------------------------------------------------------
    // 代理序列时间网格
    // ------------------------------------------------------------------

    /** 逐抽样的段内深度/年龄查找表，走廊/生长率/代理网格共用同一批随机抽样。 */
    private record DrawLookup(List<Map<String, double[]>> segAges,
                              List<Map<String, double[]>> segDepths,
                              Map<String, List<DateSample>> bySeg) {}


    private static List<ModelResult.ProxyGridPoint> buildProxyGrid(
            List<ProxyPoint> proxy, DrawLookup lookup,
            ModelConfig cfg, int draws, double ageLo, double ageHi) {
        List<ModelResult.ProxyGridPoint> out = new ArrayList<>();
        if (Double.isInfinite(ageLo) || Double.isInfinite(ageHi) || ageHi <= ageLo) {
            return out;
        }
        List<String> segmentsOrdered = new ArrayList<>(lookup.bySeg().keySet());

        double start = Math.floor(ageLo / cfg.gridStepKa()) * cfg.gridStepKa();
        for (double t = start; t <= ageHi + 1e-9; t += cfg.gridStepKa()) {
            double age = round(t);
            double[] vals = new double[draws];
            int supported = 0;
            for (int draw = 0; draw < draws; draw++) {
                vals[draw] = Double.NaN;
                Map<String, double[]> agesMap = lookup.segAges().get(draw);
                Map<String, double[]> depthsMap = lookup.segDepths().get(draw);
                for (String sid : segmentsOrdered) {
                    double[] ages = agesMap.get(sid);
                    double[] depths = depthsMap.get(sid);
                    if (ages == null || ages.length == 0) {
                        continue;
                    }
                    if (age < ages[0] - 1e-9 || age > ages[ages.length - 1] + 1e-9) {
                        continue;
                    }
                    supported++;
                    double depth = invertAgeToDepth(depths, ages, age);
                    double v = interpProxy(proxy, depth);
                    vals[draw] = v;
                    break; // 分支内段互不重叠，不跨间断
                }
            }
            double coverage = supported / (double) draws;
            double[] valid = clean(vals);
            if (valid.length == 0) {
                out.add(new ModelResult.ProxyGridPoint(age, Double.NaN, Double.NaN,
                        Double.NaN, Double.NaN, Double.NaN, coverage, true));
            } else {
                double mean = Percentiles.mean(valid);
                double std = Percentiles.std(valid, mean);
                double[] qs = Percentiles.quantiles(valid, 0.025, 0.5, 0.975);
                out.add(new ModelResult.ProxyGridPoint(age, mean, std, qs[0], qs[1], qs[2],
                        coverage, false));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 数值工具
    // ------------------------------------------------------------------

    /** 加权 PAVA：将 y 约束为沿深度非递减（单调增大），同深度块按权重合并。 */
    static void pavNonDecreasing(double[] y, double[] w) {
        Deque<Block> blocks = new ArrayDeque<>();
        for (int i = 0; i < y.length; i++) {
            Block b = new Block();
            b.value = y[i];
            b.weight = w[i];
            blocks.addLast(b);
            while (blocks.size() >= 2) {
                Block last = blocks.pollLast();
                Block prev = blocks.peekLast();
                if (prev.value <= last.value + 1e-12) {
                    blocks.addLast(last);
                    break;
                }
                double nw = prev.weight + last.weight;
                prev.value = (prev.value * prev.weight + last.value * last.weight) / nw;
                prev.weight = nw;
            }
        }
        int i = 0;
        for (Block b : blocks) {
            y[i++] = b.value;
        }
    }

    private static final class Block {
        double value;
        double weight;
    }

    /** 深度->年龄的分段线性插值（调用方保证在支持深度范围内）。 */
    static double interpDepth(double[] depths, double[] ages, double depth) {
        if (depth <= depths[0]) {
            return ages[0];
        }
        if (depth >= depths[depths.length - 1]) {
            return ages[ages.length - 1];
        }
        for (int k = 0; k + 1 < depths.length; k++) {
            if (depth >= depths[k] && depth <= depths[k + 1]) {
                double f = (depth - depths[k]) / (depths[k + 1] - depths[k]);
                return ages[k] + f * (ages[k + 1] - ages[k]);
            }
        }
        return Double.NaN;
    }

    /** 年龄->深度反演（段内线性）。 */
    static double invertAgeToDepth(double[] depths, double[] ages, double age) {
        if (age <= ages[0]) {
            return depths[0];
        }
        if (age >= ages[ages.length - 1]) {
            return depths[depths.length - 1];
        }
        for (int k = 0; k + 1 < ages.length; k++) {
            if (age >= ages[k] && age <= ages[k + 1]) {
                double f = (age - ages[k]) / (ages[k + 1] - ages[k]);
                return depths[k] + f * (depths[k + 1] - depths[k]);
            }
        }
        return Double.NaN;
    }

    /** 最小二乘线性拟合 age = a + b*depth，并求边界处年龄（仅用同段锦标）。 */
    static double fitEdge(double[] depths, double[] ages, double edgeDepth) {
        int n = depths.length;
        if (n < 2) {
            return Double.NaN;
        }
        double mx = 0, my = 0;
        for (int k = 0; k < n; k++) {
            mx += depths[k];
            my += ages[k];
        }
        mx /= n;
        my /= n;
        double sxx = 0, sxy = 0;
        for (int k = 0; k < n; k++) {
            sxx += (depths[k] - mx) * (depths[k] - mx);
            sxy += (depths[k] - mx) * (ages[k] - my);
        }
        if (sxx <= 0) {
            return Double.NaN;
        }
        double b = sxy / sxx;
        double a = my - b * mx;
        return a + b * edgeDepth;
    }

    static double interpProxy(List<ProxyPoint> proxy, double depth) {
        if (proxy.isEmpty()) {
            return Double.NaN;
        }
        if (depth <= proxy.get(0).depthMm()) {
            return proxy.get(0).value();
        }
        if (depth >= proxy.get(proxy.size() - 1).depthMm()) {
            return proxy.get(proxy.size() - 1).value();
        }
        for (int k = 0; k + 1 < proxy.size(); k++) {
            ProxyPoint p0 = proxy.get(k);
            ProxyPoint p1 = proxy.get(k + 1);
            if (depth >= p0.depthMm() && depth <= p1.depthMm()) {
                double f = (depth - p0.depthMm()) / (p1.depthMm() - p0.depthMm());
                return p0.value() + f * (p1.value() - p0.value());
            }
        }
        return Double.NaN;
    }

    private static double[] clean(double[] vals) {
        int n = 0;
        for (double v : vals) {
            if (!Double.isNaN(v)) {
                n++;
            }
        }
        double[] out = new double[n];
        int i = 0;
        for (double v : vals) {
            if (!Double.isNaN(v)) {
                out[i++] = v;
            }
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static String fmt(double v) {
        return String.format("%.3f", v);
    }
}
