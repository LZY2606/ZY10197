package com.drip.model;

import java.util.List;
import java.util.Map;

/** 年代模型完整结果（可序列化、可导出、可复核）。 */
public class ModelResult {

    /** 同深度年代冲突证据：不放大任何误差。 */
    public record Conflict(String depthMm, List<Member> members, double minAbsDiffKa,
                           List<PairEvidence> incompatiblePairs) {
        public record Member(String dateId, double depthMm, double ageKa, double ageSigmaKa,
                             String corrGroup) {}
        public record PairEvidence(String dateIdA, String dateIdB, double ageA, double ageB,
                                   double diffKa, double sigmaDiffKa, double zScore, boolean incompatible) {}
    }

    /** 同深度年代分支：各分支独立保留，不平均成假曲线。 */
    public record Branch(String key, String label, List<String> dateIds,
                         List<Conflict> conflicts,
                         List<CorridorPoint> corridor,
                         List<GrowthRatePoint> growthRates,
                         List<GapStat> gaps,
                         List<ProxyGridPoint> proxyGrid) {}

    /** 年龄走廊在某深度的分位数与覆盖率；落在无年代支持区域 markedMissing=true。 */
    public record CorridorPoint(double depthMm, double q025, double q500, double q975,
                                double coverage, boolean unsupported, String segmentId) {}

    /** 局部生长率（mm/yr，正值）分位数；仅由同生长段内部斜率得到，绝不用间断两侧外推。 */
    public record GrowthRatePoint(String segmentId, double depthMm,
                                  double q025, double q500, double q975, double coverage) {}

    /** 间断时长统计（ka）。 */
    public record GapStat(String hiatusId, double topDepthMm, double bottomDepthMm,
                          boolean topOpen, boolean bottomOpen,
                          Double projectedQ025, Double projectedQ500, Double projectedQ975,
                          Double nearestNeighborQ025, Double nearestNeighborQ500, Double nearestNeighborQ975,
                          double coverage, String interpretation) {}

    /** 规则时间网格上的代理指标统计；coverage 为该格点有年代支持的抽样比例。 */
    public record ProxyGridPoint(double ageKa, double mean, double std,
                                 double q025, double q500, double q975,
                                 double coverage, boolean missing) {}

    public ModelConfig config;
    public List<Branch> branches = List.of();
    public Map<String, Object> meta = Map.of();
}
