package com.drip.model;

/**
 * 年代模型运行参数。
 *
 * @param draws          Monte Carlo 抽样次数
 * @param seed           固定随机种子（重放一致）
 * @param gridStepKa     代理序列输出的规则时间网格步长（ka）
 * @param corridorStepMm 年龄走廊深度网格步长（mm）
 */
public record ModelConfig(int draws, long seed, double gridStepKa, double corridorStepMm) {

    public static ModelConfig defaults() {
        return new ModelConfig(2000, 20260921L, 0.5, 1.0);
    }
}
