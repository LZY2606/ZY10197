package com.drip.engine;

import java.util.Arrays;

/** numpy 默认（线性插值，C=1）分位数与样本统计。 */
public final class Percentiles {

    private Percentiles() {}

    public static double q(double[] sortedAsc, double p) {
        int n = sortedAsc.length;
        if (n == 0) {
            return Double.NaN;
        }
        if (n == 1) {
            return sortedAsc[0];
        }
        double h = (n - 1) * p;
        int lo = (int) Math.floor(h);
        int hi = lo + 1;
        double frac = h - lo;
        if (hi >= n) {
            return sortedAsc[n - 1];
        }
        return sortedAsc[lo] * (1.0 - frac) + sortedAsc[hi] * frac;
    }

    public static double[] quantiles(double[] values, double... ps) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double[] out = new double[ps.length];
        for (int i = 0; i < ps.length; i++) {
            out[i] = q(sorted, ps[i]);
        }
        return out;
    }

    public static double mean(double[] values) {
        double s = 0;
        for (double v : values) {
            s += v;
        }
        return s / values.length;
    }

    public static double std(double[] values, double mean) {
        double s = 0;
        for (double v : values) {
            double d = v - mean;
            s += d * d;
        }
        return Math.sqrt(s / values.length);
    }
}
