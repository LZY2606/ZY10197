package com.example.rocklayer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AgeModelService {
    private static final double Z_LIMIT = 3.0;
    private static final double YOUNG_PRIOR_MEAN = 0.08;
    private static final double YOUNG_PRIOR_SIGMA = 0.004;
    private static final double OLD_PRIOR_MEAN = 0.02;
    private static final double OLD_PRIOR_SIGMA = 0.003;
    private final ObjectMapper mapper;

    public AgeModelService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ObjectNode run(JsonNode s) {
        List<DatePoint> allDates = readDates(s);
        List<ProxyPoint> proxies = readProxies(s);
        Hiatus hiatus = readHiatus(s);
        double sharedSigma = optional(s, "sharedCorrectionSigma", 30.0);
        int branchCount = (int) optional(s, "branchCount", 160.0);
        double gridStart = optional(s, "gridStart", 0.0);
        double gridStop = optional(s, "gridStop", 6500.0);
        double gridStep = optional(s, "gridStep", 250.0);

        validate(allDates, proxies, hiatus, branchCount, gridStart, gridStop, gridStep);
        List<DatePoint> active = allDates.stream().filter(d -> d.active).sorted(Comparator.comparingDouble(d -> d.depth)).toList();
        List<ConflictEvidence> conflicts = findDepthConflicts(active, sharedSigma);
        ObjectNode root = mapper.createObjectNode();
        root.put("status", conflicts.isEmpty() ? "OK" : "CONFLICT");
        root.put("method", "deterministic correlated Gaussian branches; segment-wise PAVA monotonic depth-age curves; no hiatus interpolation");
        root.put("branchCount", branchCount);
        root.set("segments", mapper.createArrayNode());
        root.set("conflicts", conflictsToJson(conflicts));
        if (!conflicts.isEmpty()) {
            root.putObject("message").put("summary", "同深度年代锦标年龄区间不相容；模型保留冲突证据，不放大全部误差，也不输出伪插值年代。");
            return root;
        }

        List<SegmentWork> works = assignAndPrepare(active, hiatus, sharedSigma);
        samplePhysicalBranches(works, branchCount, hiatus, sharedSigma, active);
        propagateProxies(works, proxies);
        Map<String, List<Double>> gridValues = new TreeMap<>();
        Map<String, GridMeta> gridMeta = new TreeMap<>();
        for (SegmentWork work : works) {
            for (double age = gridStart; age <= gridStop + 0.0001; age += gridStep) {
                String key = round(age, 6) + "#" + work.index;
                gridValues.putIfAbsent(key, new ArrayList<>());
                gridMeta.putIfAbsent(key, new GridMeta(round(age, 6), work.index));
            }
            addGrid(work, gridValues);
        }

        root.set("segments", segmentsToJson(works));
        root.set("hiatusDuration", hiatusDuration(works, hiatus));
        root.set("ageCorridor", ageCorridor(works));
        root.set("growthRates", growthRates(works));
        root.set("proxyGrid", proxyGrid(gridValues, gridMeta, branchCount, gridStep));
        root.set("branchSeries", branchSeries(works));
        root.set("hiatus", hiatusJson(hiatus));
        root.putArray("interpretationRules")
                .add("各生长段独立建立单调深度-年龄关系，间断内部不生成年龄。")
                .add("开边界不向外推年龄；闭边界仅使用局部生长率先验外延，不与另一侧插值连接。")
                .add("共享校正组按相关误差同步扰动；代理格点的可覆盖率是有效分支比例，缺失分支不补值。");
        return root;
    }

    private record DatePoint(String id, double depth, double age, double sigma, String group, boolean active, String kind) {}
    private record ProxyPoint(double depth, double value) {}
    private record Hiatus(double youngDepth, double oldDepth, boolean youngOpen, boolean oldOpen,
                          boolean youngOuterClosed, boolean oldOuterClosed) {}
    private record ConflictEvidence(String type, String dateA, String dateB, double depth, double ageA, double ageB,
                                    double difference, double differenceSigma, double z, String intervalA, String intervalB,
                                    String reason) {}
    private static final class SegmentWork {
        int index;
        String name;
        double minDepth;
        double maxDepth;
        List<DatePoint> dates = new ArrayList<>();
        Map<String, Double> groupOffsets = new HashMap<>();
        List<double[]> knots = new ArrayList<>();
        double[][] anchorAges;
        List<ProxyPoint> proxies = new ArrayList<>();
        double[] proxyDepth;
        double[] proxyValue;
        double[][] proxyAges;
        double[][] valuesAtAge;
        double[] supportStart;
        double[] supportEnd;
        double[] rateMin;
        double[] rateQ25;
        double[] rateMedian;
        double[] rateQ75;
        double[] rateMax;
    }

    private List<DatePoint> readDates(JsonNode s) {
        List<DatePoint> dates = new ArrayList<>();
        JsonNode arr = s.get("dates");
        if (arr == null || !arr.isArray()) throw new IllegalArgumentException("dates must be an array");
        for (JsonNode d : arr) {
            dates.add(new DatePoint(req(d, "id"), num(d, "depth"), num(d, "ageBP"), num(d, "ageSigma"),
                    d.has("correctionGroup") && !d.get("correctionGroup").isNull() ? d.get("correctionGroup").asText() : null,
                    !d.has("active") || d.get("active").asBoolean(),
                    d.has("kind") ? d.get("kind").asText() : "u-th"));
        }
        return dates;
    }

    private List<ProxyPoint> readProxies(JsonNode s) {
        List<ProxyPoint> values = new ArrayList<>();
        JsonNode arr = s.get("proxies");
        if (arr == null || !arr.isArray()) throw new IllegalArgumentException("proxies must be an array");
        for (JsonNode p : arr) values.add(new ProxyPoint(num(p, "depth"), num(p, "value")));
        values.sort(Comparator.comparingDouble(p -> p.depth));
        return values;
    }

    private Hiatus readHiatus(JsonNode s) {
        JsonNode h = s.get("hiatus");
        if (h == null) throw new IllegalArgumentException("hiatus is required");
        return new Hiatus(num(h, "youngDepth"), num(h, "oldDepth"),
                h.path("youngSideOpen").asBoolean(false), h.path("oldSideOpen").asBoolean(false),
                h.path("youngOuterClosed").asBoolean(true), h.path("oldOuterClosed").asBoolean(true));
    }

    private void validate(List<DatePoint> dates, List<ProxyPoint> proxies, Hiatus h, int branches,
                          double gridStart, double gridStop, double gridStep) {
        if (branches < 8 || branches > 1000) throw new IllegalArgumentException("branchCount must be between 8 and 1000");
        if (gridStep <= 0 || gridStop < gridStart) throw new IllegalArgumentException("gridStart/gridStep/gridStop are invalid");
        if (h.youngDepth >= h.oldDepth) throw new IllegalArgumentException("间断 youngDepth 必须小于 oldDepth");
        if (dates.isEmpty()) throw new IllegalArgumentException("至少需要一个年代控制点");
        Set<String> ids = new HashSet<>();
        for (DatePoint d : dates) {
            if (!ids.add(d.id)) throw new IllegalArgumentException("重复年代锦标 ID: " + d.id);
            if (d.sigma < 0) throw new IllegalArgumentException(d.id + " 的年龄不确定性不能为负");
            if (d.depth == h.youngDepth || d.depth == h.oldDepth) throw new IllegalArgumentException("年代控制点不能放在间断开闭边界上: " + d.id);
            if (d.depth > h.youngDepth && d.depth < h.oldDepth) throw new IllegalArgumentException("年代控制点落入间断内部: " + d.id);
        }
        for (ProxyPoint p : proxies) {
            if (p.depth > h.youngDepth && p.depth < h.oldDepth) throw new IllegalArgumentException("代理样本落入间断内部: depth=" + p.depth);
            if (p.depth == h.youngDepth || p.depth == h.oldDepth) throw new IllegalArgumentException("代理样本不能放在间断开闭边界上: depth=" + p.depth);
        }
    }

    private List<ConflictEvidence> findDepthConflicts(List<DatePoint> dates, double sharedSigma) {
        List<ConflictEvidence> out = new ArrayList<>();
        for (int i = 0; i < dates.size(); i++) {
            for (int j = i + 1; j < dates.size(); j++) {
                DatePoint a = dates.get(i), b = dates.get(j);
                if (Math.abs(a.depth - b.depth) > 1e-9) continue;
                double variance = a.sigma * a.sigma + b.sigma * b.sigma;
                if (a.group != null && a.group.equals(b.group)) {
                    variance -= 2 * sharedSigma * sharedSigma;
                } else if (a.group != null && b.group != null) {
                    variance += 2 * sharedSigma * sharedSigma;
                }
                double ds = Math.sqrt(Math.max(variance, 0));
                double diff = Math.abs(a.age - b.age);
                double z = ds == 0 ? (diff > 0 ? Double.POSITIVE_INFINITY : 0) : diff / ds;
                boolean disjoint = diff > 2 * Math.sqrt(a.sigma * a.sigma + b.sigma * b.sigma);
                if (z >= Z_LIMIT && disjoint) {
                    out.add(new ConflictEvidence("SAME_DEPTH_AGE_INCOMPATIBLE", a.id, b.id, a.depth, a.age, b.age, diff, ds, z,
                            interval(a), interval(b), "同深度年龄均值差异达到 3σ 且 2σ 年龄区间不相交；未调整任何输入误差。"));
                }
            }
        }
        return out;
    }

    private List<SegmentWork> assignAndPrepare(List<DatePoint> active, Hiatus h, double sharedSigma) {
        SegmentWork young = new SegmentWork(), old = new SegmentWork();
        young.index = 0; young.name = "年轻生长段"; young.minDepth = Double.NEGATIVE_INFINITY; young.maxDepth = h.youngDepth;
        old.index = 1; old.name = "较老生长段"; old.minDepth = h.oldDepth; old.maxDepth = Double.POSITIVE_INFINITY;
        for (DatePoint d : active) {
            if (d.depth < h.youngDepth) young.dates.add(d);
            else if (d.depth > h.oldDepth) old.dates.add(d);
        }
        if (young.dates.isEmpty() || old.dates.isEmpty()) {
            throw new IllegalArgumentException("调整间断后每个生长段都必须至少保留一个有效年代控制点；本模型不跨间断借用年代。");
        }
        prepareKnots(young, h, true, sharedSigma);
        prepareKnots(old, h, false, sharedSigma);
        return List.of(young, old);
    }

    private void prepareKnots(SegmentWork w, Hiatus h, boolean youngSide, double sharedSigma) {
        DatePoint outer = youngSide
                ? w.dates.stream().min(Comparator.comparingDouble(d -> d.depth)).orElseThrow()
                : w.dates.stream().max(Comparator.comparingDouble(d -> d.depth)).orElseThrow();
        DatePoint inner = youngSide
                ? w.dates.stream().max(Comparator.comparingDouble(d -> d.depth)).orElseThrow()
                : w.dates.stream().min(Comparator.comparingDouble(d -> d.depth)).orElseThrow();
        w.knots.add(new double[]{outer.depth, outer.age, outer.sigma, knotCode(outer)});
        if (outer != inner) {
            for (DatePoint d : w.dates) {
                if (d != outer && d != inner) w.knots.add(new double[]{d.depth, d.age, d.sigma, knotCode(d)});
            }
            w.knots.add(new double[]{inner.depth, inner.age, inner.sigma, knotCode(inner)});
        }
        w.knots.sort(Comparator.comparingDouble(k -> k[0]));
        double boundaryDepth = youngSide ? h.youngDepth : h.oldDepth;
        boolean boundaryClosed = youngSide ? !h.youngOpen : !h.oldOpen;
        if (boundaryClosed) {
            w.knots.add(new double[]{boundaryDepth, inner.age + (boundaryDepth - inner.depth) / priorMean(youngSide),
                    boundaryAgeSigma(inner.sigma, Math.abs(boundaryDepth - inner.depth), youngSide), youngSide ? -2 : -3});
            w.knots.sort(Comparator.comparingDouble(k -> k[0]));
        }
    }


    private double priorMean(boolean youngSide) {
        return youngSide ? YOUNG_PRIOR_MEAN : OLD_PRIOR_MEAN;
    }

    private double priorSigma(boolean youngSide) {
        return youngSide ? YOUNG_PRIOR_SIGMA : OLD_PRIOR_SIGMA;
    }

    private double boundaryAgeSigma(double innerSigma, double distance, boolean youngSide) {
        double meanAge = distance / priorMean(youngSide);
        double rateRelativeSigma = priorSigma(youngSide) / priorMean(youngSide);
        return Math.sqrt(innerSigma * innerSigma + meanAge * meanAge * rateRelativeSigma * rateRelativeSigma);
    }

    private int knotCode(DatePoint date) {
        return "fixed".equals(date.kind) ? 0 : groupCode(date.group);
    }

    private int groupCode(String group) {
        if (group == null || group.isBlank()) return -1;
        int code = 0;
        for (char c : group.toCharArray()) code = 31 * code + c;
        return code;
    }

    private void samplePhysicalBranches(List<SegmentWork> works, int branchCount, Hiatus h,
                                        double sharedSigma, List<DatePoint> active) {
        for (SegmentWork work : works) work.anchorAges = new double[work.knots.size()][branchCount];
        Set<Integer> groups = new TreeSet<>();
        for (DatePoint date : active) {
            if (date.group != null && !date.group.isBlank()) groups.add(groupCode(date.group));
        }
        DeterministicNormal groupNormal = new DeterministicNormal(721337L);
        int accepted = 0;
        int attempt = 0;
        while (accepted < branchCount) {
            if (attempt++ > branchCount * 100) {
                throw new IllegalArgumentException("闭边界先验下间断长期为负，说明边界设定不相容；请改开闭边界或端点，不以误差放大强行通过。");
            }
            Map<Integer, Double> groupOffsets = new HashMap<>();
            for (Integer group : groups) groupOffsets.put(group, groupNormal.next());
            Map<SegmentWork, double[]> candidate = new HashMap<>();
            for (SegmentWork work : works) {
                candidate.put(work, sampleSegmentBranch(work, sharedSigma, groupOffsets, attempt, work.index, work.index == 0));
            }
            if (candidate.values().stream().flatMapToDouble(Arrays::stream).anyMatch(age -> age < -1e-9)) {
                continue;
            }
            if (!h.youngOpen && !h.oldOpen) {
                SegmentWork young = works.get(0);
                SegmentWork old = works.get(1);
                double youngBoundary = candidate.get(young)[wIndexByDepth(young, h.youngDepth)];
                double oldBoundary = candidate.get(old)[wIndexByDepth(old, h.oldDepth)];
                if (youngBoundary <= oldBoundary) continue;
            }
            for (SegmentWork work : works) {
                double[] ages = candidate.get(work);
                for (int k = 0; k < ages.length; k++) work.anchorAges[k][accepted] = ages[k];
            }
            accepted++;
        }
    }

    private double[] sampleSegmentBranch(SegmentWork w, double sharedSigma, Map<Integer, Double> groupOffsets,
                                         int attempt, int segmentIndex, boolean youngSide) {
        DeterministicNormal normal = new DeterministicNormal(199937L + segmentIndex * 100003L + attempt * 7919L);
        DeterministicNormal rateNormal = new DeterministicNormal(551177L + segmentIndex * 100009L + attempt * 104729L);
        double[] ages = new double[w.knots.size()];
        for (int i = 0; i < w.knots.size(); i++) {
            double[] k = w.knots.get(i);
            int code = (int) k[3];
            if (code == -2 || code == -3) continue;
            double value = k[1];
            if (code == 0) {
                ages[i] = value;
                continue;
            }
            if (code > 0) {
                double idiosyncratic = Math.sqrt(Math.max(0, k[2] * k[2] - sharedSigma * sharedSigma));
                value += groupOffsets.getOrDefault(code, 0.0) * sharedSigma + normal.next() * idiosyncratic;
            } else {
                value += normal.next() * k[2];
            }
            ages[i] = value;
        }
        for (int i = 0; i < w.knots.size(); i++) {
            int code = (int) w.knots.get(i)[3];
            if (code != -2 && code != -3) continue;
            int neighbor = code == -2 ? i - 1 : i + 1;
            double distance = w.knots.get(i)[0] - w.knots.get(neighbor)[0];
            double mean = priorMean(youngSide);
            double rate = Math.min(mean * 2.0, Math.max(mean / 2.0, mean + priorSigma(youngSide) * rateNormal.next()));
            ages[i] = ages[neighbor] + distance / rate;
        }
        return pava(ages, w.knots.stream().mapToDouble(k -> k[3] == 0 ? 1 : 0).toArray());
    }

    private double[] pava(double[] values, double[] fixed) {
        double[] out = values.clone();
        int[] counts = new int[out.length];
        Arrays.fill(counts, 1);
        for (int end = 1; end < out.length; end++) {
            int cursor = end;
            while (cursor > 0 && out[cursor - 1] > out[cursor] + 1e-9) {
                if (fixed[cursor - 1] > 0 || fixed[cursor] > 0) break;
                double merged = (out[cursor - 1] * counts[cursor - 1] + out[cursor] * counts[cursor])
                        / (counts[cursor - 1] + counts[cursor]);
                out[cursor - 1] = merged;
                counts[cursor - 1] += counts[cursor];
                cursor--;
            }
        }
        for (int i = 1; i < out.length; i++) {
            if (out[i] < out[i - 1] && fixed[i] > 0) out[i - 1] = out[i];
        }
        return out;
    }

    private void propagateProxies(List<SegmentWork> works, List<ProxyPoint> all) {
        for (SegmentWork w : works) {
            List<ProxyPoint> ps = all.stream().filter(p -> p.depth > w.minDepth && p.depth < w.maxDepth).toList();
            w.proxies = ps;
            w.proxyDepth = ps.stream().mapToDouble(p -> p.depth).toArray();
            w.proxyValue = ps.stream().mapToDouble(p -> p.value).toArray();
            int n = ps.size();
            w.proxyAges = new double[n][];
            w.valuesAtAge = new double[n][];
            w.supportStart = new double[n];
            w.supportEnd = new double[n];
            for (int i = 0; i < n; i++) {
                double depth = w.proxyDepth[i];
                double[] ages = interpAges(w, depth);
                if (ages != null) {
                    w.proxyAges[i] = ages;
                    w.valuesAtAge[i] = ps.get(i).value == ps.get(i).value ? new double[ages.length] : new double[0];
                    for (int b = 0; b < ages.length; b++) w.valuesAtAge[i][b] = ps.get(i).value;
                    List<Double> finite = new ArrayList<>();
                    for (double a : ages) if (!Double.isNaN(a)) finite.add(a);
                    if (!finite.isEmpty()) {
                        w.supportStart[i] = finite.stream().min(Double::compare).orElse(Double.NaN);
                        w.supportEnd[i] = finite.stream().max(Double::compare).orElse(Double.NaN);
                    } else Arrays.fill(w.supportStart, Double.NaN);
                } else {
                    w.proxyAges[i] = new double[0];
                    w.valuesAtAge[i] = new double[0];
                    w.supportStart[i] = Double.NaN;
                    w.supportEnd[i] = Double.NaN;
                }
            }
        }
    }

    private double[] interpAges(SegmentWork w, double depth) {
        int branches = w.anchorAges[0].length;
        double minKnot = w.knots.get(0)[0];
        double maxKnot = w.knots.get(w.knots.size() - 1)[0];
        if (depth < minKnot - 1e-9 || depth > maxKnot + 1e-9) return null;
        int upper = 0;
        while (upper < w.knots.size() && w.knots.get(upper)[0] < depth) upper++;
        double[] out = new double[branches];
        if (upper < w.knots.size() && Math.abs(w.knots.get(upper)[0] - depth) <= 1e-9) {
            for (int b = 0; b < branches; b++) out[b] = w.anchorAges[upper][b];
            return out;
        }
        int lower = upper - 1;
        double d0 = w.knots.get(lower)[0], d1 = w.knots.get(upper)[0];
        double f = (depth - d0) / (d1 - d0);
        for (int b = 0; b < branches; b++) {
            double a0 = w.anchorAges[lower][b], a1 = w.anchorAges[upper][b];
            if (a1 < a0) {
                double t = a0; a0 = a1; a1 = t;
            }
            out[b] = a0 + f * (a1 - a0);
        }
        return out;
    }

    private void addGrid(SegmentWork w, Map<String, List<Double>> grid) {
        int branches = w.anchorAges[0].length;
        for (Map.Entry<String, List<Double>> entry : grid.entrySet()) {
            if (!entry.getKey().endsWith("#" + w.index)) continue;
            double age = Double.parseDouble(entry.getKey().substring(0, entry.getKey().indexOf('#')));
            for (int b = 0; b < branches; b++) {
                Double value = proxyAtAge(w, b, age);
                if (value != null) entry.getValue().add(value);
            }
        }
    }

    private record GridMeta(double age, int segmentIndex) {}

    private Double proxyAtAge(SegmentWork w, int branch, double age) {
        double bestLeftAge = -Double.MAX_VALUE, bestRightAge = Double.MAX_VALUE;
        double bestLeftValue = Double.NaN, bestRightValue = Double.NaN;
        for (int i = 0; i < w.proxyDepth.length; i++) {
            if (w.proxyAges[i] == null || i >= w.proxyAges[i].length) continue;
            double sampleAge = w.proxyAges[i][branch];
            double value = w.proxyValue[i];
            if (Double.isNaN(sampleAge)) continue;
            if (Math.abs(sampleAge - age) <= 1e-9) return value;
            if (sampleAge < age && sampleAge > bestLeftAge) {
                bestLeftAge = sampleAge; bestLeftValue = value;
            }
            if (sampleAge > age && sampleAge < bestRightAge) {
                bestRightAge = sampleAge; bestRightValue = value;
            }
        }
        if (Double.isNaN(bestLeftValue) || Double.isNaN(bestRightValue)) return null;
        double f = (age - bestLeftAge) / (bestRightAge - bestLeftAge);
        if (f < -1e-9 || f > 1.000000001) return null;
        return bestLeftValue + f * (bestRightValue - bestLeftValue);
    }

    private String segmentKey(SegmentWork w) {
        return segmentKey(w.index);
    }

    private String segmentKey(int index) {
        return "segment-" + index;
    }

    private ArrayNode segmentsToJson(List<SegmentWork> works) {
        ArrayNode arr = mapper.createArrayNode();
        for (SegmentWork w : works) {
            ObjectNode seg = arr.addObject();
            seg.put("index", w.index);
            seg.put("name", w.name);
            seg.put("controlDepthMin", w.knots.get(0)[0]);
            seg.put("controlDepthMax", w.knots.get(w.knots.size() - 1)[0]);
            ArrayNode anchors = seg.putArray("anchors");
            for (int i = 0; i < w.knots.size(); i++) {
                ObjectNode a = anchors.addObject();
                a.put("depth", round(w.knots.get(i)[0], 6));
                double[] q = quantiles(w.anchorAges[i]);
                a.put("q025", q[0]); a.put("median", q[1]); a.put("q975", q[2]);
                a.put("min", q[3]); a.put("max", q[4]);
            }
            ArrayNode proxy = seg.putArray("proxySamples");
            for (int i = 0; i < w.proxyDepth.length; i++) {
                ObjectNode p = proxy.addObject();
                p.put("depth", round(w.proxyDepth[i], 6));
                p.put("value", round(w.proxyValue[i], 6));
                if (w.proxyAges[i] == null || w.proxyAges[i].length == 0) {
                    p.putNull("ageQ025"); p.putNull("ageMedian"); p.putNull("ageQ975");
                    p.put("coverageRate", 0);
                } else {
                    double[] q = quantiles(w.proxyAges[i]);
                    p.put("ageQ025", q[0]); p.put("ageMedian", q[1]); p.put("ageQ975", q[2]);
                    p.put("coverageRate", (double) finite(w.proxyAges[i]).size() / w.anchorAges[0].length);
                }
            }
        }
        return arr;
    }

    private ObjectNode hiatusDuration(List<SegmentWork> works, Hiatus h) {
        SegmentWork young = works.get(0), old = works.get(1);
        ObjectNode out = mapper.createObjectNode();
        boolean youngClosed = !h.youngOpen;
        boolean oldClosed = !h.oldOpen;
        out.put("youngSideClosed", youngClosed);
        out.put("oldSideClosed", oldClosed);
        if (!youngClosed || !oldClosed) {
            out.put("estimable", false);
            out.put("status", h.youngOpen && h.oldOpen ? "两侧开边界：间断时长仅保留下界，不做跨边界估计。"
                    : "一侧开边界：间断时长不可由对侧内插估计，仅保留非负约束。");
            out.putNull("q025"); out.putNull("median"); out.putNull("q975");
            return out;
        }
        int youngIndex = wIndexByDepth(young, h.youngDepth);
        int oldIndex = wIndexByDepth(old, h.oldDepth);
        List<Double> gaps = new ArrayList<>();
        for (int b = 0; b < young.anchorAges[youngIndex].length; b++) {
            double gap = young.anchorAges[youngIndex][b] - old.anchorAges[oldIndex][b];
            if (gap >= 0) gaps.add(gap);
        }
        if (gaps.isEmpty()) {
            out.put("estimable", false);
            out.put("status", "闭边界分支均违反非负间断约束，返回结构冲突证据，不做误差放大。");
            return out;
        }
        double[] q = quantiles(gaps.stream().mapToDouble(Double::doubleValue).toArray());
        out.put("estimable", true);
        out.put("q025", q[0]); out.put("median", q[1]); out.put("q975", q[2]);
        out.put("coverageRate", (double) gaps.size() / young.anchorAges[youngIndex].length);
        out.put("status", "两侧闭边界：分别向间断边界外延后估计间断时长；两侧生长段仍不连接。");
        return out;
    }

    private int wIndexByDepth(SegmentWork w, double depth) {
        for (int i = 0; i < w.knots.size(); i++) if (Math.abs(w.knots.get(i)[0] - depth) < 1e-9) return i;
        throw new IllegalStateException("Missing boundary knot");
    }

    private ArrayNode ageCorridor(List<SegmentWork> works) {
        ArrayNode rows = mapper.createArrayNode();
        for (SegmentWork w : works) {
            double min = w.knots.get(0)[0], max = w.knots.get(w.knots.size() - 1)[0];
            for (int k = 0; k <= 12; k++) {
                double depth = min + (max - min) * k / 12;
                double[] ages = interpAges(w, depth);
                if (ages == null) continue;
                List<Double> finite = finite(ages);
                if (finite.isEmpty()) continue;
                double[] q = quantiles(ages);
                ObjectNode row = rows.addObject();
                row.put("segmentIndex", w.index);
                row.put("depth", round(depth, 6));
                row.put("q025", q[0]); row.put("median", q[1]); row.put("q975", q[2]);
                row.put("coverageRate", (double) finite.size() / ages.length);
            }
        }
        return rows;
    }

    private ArrayNode growthRates(List<SegmentWork> works) {
        ArrayNode rows = mapper.createArrayNode();
        for (SegmentWork w : works) {
            for (int i = 0; i + 1 < w.knots.size(); i++) {
                List<Double> rates = new ArrayList<>();
                for (int b = 0; b < w.anchorAges[i].length; b++) {
                    double ageGap = Math.abs(w.anchorAges[i + 1][b] - w.anchorAges[i][b]);
                    double depthGap = Math.abs(w.knots.get(i + 1)[0] - w.knots.get(i)[0]);
                    if (ageGap > 1e-12) rates.add(depthGap / ageGap);
                }
                if (rates.isEmpty()) continue;
                double[] q = quantiles(rates);
                ObjectNode row = rows.addObject();
                row.put("segmentIndex", w.index);
                row.put("depthStart", round(w.knots.get(i)[0], 6));
                row.put("depthEnd", round(w.knots.get(i + 1)[0], 6));
                row.put("q025", q[0]); row.put("median", q[1]); row.put("q975", q[2]);
                row.put("coverageRate", (double) rates.size() / w.anchorAges[i].length);
            }
        }
        return rows;
    }

    private ArrayNode proxyGrid(Map<String, List<Double>> gridValues, Map<String, GridMeta> gridMeta,
                                int branchCount, double gridStep) {
        ArrayNode rows = mapper.createArrayNode();
        gridMeta.entrySet().stream()
                .sorted(Map.Entry.<String, GridMeta>comparingByValue(Comparator.comparingDouble(GridMeta::age)
                        .thenComparingInt(GridMeta::segmentIndex)))
                .forEach(ordered -> {
            String key = ordered.getKey();
            List<Double> values = gridValues.get(key);
            GridMeta meta = ordered.getValue();
            ObjectNode row = rows.addObject();
            row.put("age", meta.age());
            row.put("segmentIndex", meta.segmentIndex());
            row.put("segment", segmentKey(meta.segmentIndex()));
            row.put("coverageRate", (double) values.size() / branchCount);
            row.put("sampleBranches", values.size());
            if (values.isEmpty()) {
                row.putNull("mean"); row.putNull("median"); row.putNull("q025"); row.putNull("q975");
                row.put("supported", false);
            } else {
                double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
                double[] q = quantiles(values);
                row.put("mean", round(mean, 6));
                row.put("q025", q[0]); row.put("median", q[1]); row.put("q975", q[2]);
                row.put("supported", true);
            }
        });
        return rows;
    }

    private ArrayNode branchSeries(List<SegmentWork> works) {
        ArrayNode branches = mapper.createArrayNode();
        int branchCount = works.get(0).anchorAges[0].length;
        for (int b = 0; b < branchCount; b++) {
            ObjectNode branch = branches.addObject();
            branch.put("branch", b);
            ArrayNode parts = branch.putArray("segments");
            for (SegmentWork w : works) {
                ObjectNode part = parts.addObject();
                part.put("segmentIndex", w.index);
                ArrayNode pts = part.putArray("points");
                for (int i = 0; i < w.proxyDepth.length; i++) {
                    if (w.proxyAges[i] == null || b >= w.proxyAges[i].length || Double.isNaN(w.proxyAges[i][b])) continue;
                    ObjectNode p = pts.addObject();
                    p.put("depth", round(w.proxyDepth[i], 6));
                    p.put("age", round(w.proxyAges[i][b], 6));
                    p.put("value", round(w.proxyValue[i], 6));
                }
            }
        }
        return branches;
    }

    private ArrayNode conflictsToJson(List<ConflictEvidence> conflicts) {
        ArrayNode arr = mapper.createArrayNode();
        for (ConflictEvidence c : conflicts) {
            ObjectNode n = arr.addObject();
            n.put("type", c.type);
            n.put("dateA", c.dateA);
            n.put("dateB", c.dateB);
            n.put("depth", c.depth);
            n.put("ageA", c.ageA);
            n.put("ageB", c.ageB);
            n.put("difference", round(c.difference, 6));
            n.put("differenceSigma", round(c.differenceSigma, 6));
            n.put("z", Double.isInfinite(c.z) ? 1000 : round(c.z, 6));
            n.put("intervalA", c.intervalA);
            n.put("intervalB", c.intervalB);
            n.put("reason", c.reason);
            n.put("errorInflationApplied", false);
        }
        return arr;
    }

    private ObjectNode hiatusJson(Hiatus h) {
        ObjectNode n = mapper.createObjectNode();
        n.put("youngDepth", h.youngDepth);
        n.put("oldDepth", h.oldDepth);
        n.put("youngSideOpen", h.youngOpen);
        n.put("oldSideOpen", h.oldOpen);
        return n;
    }

    private String interval(DatePoint d) {
        if (d.sigma == 0) return Double.toString(d.age);
        return round(d.age - 2 * d.sigma, 6) + "…" + round(d.age + 2 * d.sigma, 6);
    }

    private double[] quantiles(double[] values) {
        return quantiles(Arrays.stream(values).boxed().toList());
    }

    private double[] quantiles(List<Double> input) {
        List<Double> values = input.stream().filter(v -> !Double.isNaN(v)).sorted().toList();
        if (values.isEmpty()) return new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        return new double[]{round(q(values, .025), 6), round(q(values, .5), 6), round(q(values, .975), 6),
                round(values.get(0), 6), round(values.get(values.size() - 1), 6)};
    }

    private double q(List<Double> values, double p) {
        if (values.size() == 1) return values.get(0);
        double pos = p * (values.size() - 1);
        int lo = (int) Math.floor(pos);
        double f = pos - lo;
        return values.get(lo) * (1 - f) + values.get(Math.min(lo + 1, values.size() - 1)) * f;
    }

    private List<Double> finite(double[] values) {
        List<Double> out = new ArrayList<>();
        for (double v : values) if (!Double.isNaN(v) && !Double.isInfinite(v)) out.add(v);
        return out;
    }

    private double round(double v, int digits) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return v;
        double factor = Math.pow(10, digits);
        return Math.round(v * factor) / factor;
    }

    private double num(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isNumber()) throw new IllegalArgumentException("Missing numeric field: " + field);
        return v.asDouble();
    }

    private String req(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.asText().isBlank()) throw new IllegalArgumentException("Missing field: " + field);
        return v.asText();
    }

    private double optional(JsonNode node, String field, double fallback) {
        JsonNode v = node.get(field);
        return v == null || !v.isNumber() ? fallback : v.asDouble();
    }

    private static final class DeterministicNormal {
        private long state;
        DeterministicNormal(long seed) { this.state = seed == 0 ? 0x9e3779b97f4a7c15L : seed; }
        double next() {
            state += 0x9e3779b97f4a7c15L;
            long z = state;
            z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
            z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
            z ^= z >>> 31;
            double u = (z >>> 11) * 0x1.0p-53;
            return probit(Math.max(1e-12, Math.min(1 - 1e-12, u)));
        }
        private double probit(double p) {
            return 1.5707963267948966 * (p - .5) / Math.sqrt(p * (1 - p));
        }
    }
}
