package com.drip.engine;

/**
 * 固定种子的确定性均匀随机源（Numerical Recipes LCG），保证任何 JDK/机器上重放一致；
 * 不依赖 JDK 内置 Random（其算法在不同版本间可能变化）。
 */
public final class DeterministicRng {

    private long state;
    private boolean hasSpare;
    private double spare;

    public DeterministicRng(long seed) {
        this.state = (seed == 0L) ? 0x9E3779B97F4A7C15L : seed;
    }

    /** (0,1) 均匀分布。 */
    public double nextDouble() {
        state = state * 6364136223846793005L + 1442695040888963407L; // LCG 依赖 64 位环绕
        return ((state >>> 11) | 1L) * 0x1.0p-53;
    }

    /** 标准正态分布（Box-Muller）。 */
    public double nextGaussian() {
        if (hasSpare) {
            hasSpare = false;
            return spare;
        }
        double u1;
        do {
            u1 = nextDouble();
        } while (u1 <= 0.0);
        double u2 = nextDouble();
        double mag = Math.sqrt(-2.0 * Math.log(u1));
        double angle = 2.0 * Math.PI * u2;
        spare = mag * Math.sin(angle);
        hasSpare = true;
        return mag * Math.cos(angle);
    }
}
