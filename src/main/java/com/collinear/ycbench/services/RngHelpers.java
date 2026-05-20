package com.collinear.ycbench.services;

import com.collinear.ycbench.config.PyRandom;

/**
 * 世界生成器使用的采样辅助方法。镜像 {@code services/rng.py} 中的小型辅助方法
 * （{@code sample_*} 系列）。
 */
public final class RngHelpers {

    private RngHelpers() {
    }

    public static double clamp(double v, double low, double high) {
        return Math.max(low, Math.min(high, v));
    }

    public static int sampleTriangularInt(PyRandom rng, double low, double high, double mode) {
        double v = rng.triangular(low, high, mode);
        return (int) clamp(Math.round(v), low, high);
    }

    /** Right-skew (mode = high). */
    public static int sampleRightSkewTriangularInt(PyRandom rng, double low, double high) {
        return sampleTriangularInt(rng, low, high, high);
    }

    public static double sampleNormalClampedFloat(PyRandom rng, double mean, double stdev, double low, double high) {
        double v = clamp(rng.gauss(mean, stdev), low, high);
        return round4(v);
    }

    public static double sampleBetaScaled(PyRandom rng, double alpha, double beta, double scale) {
        return round4(scale * rng.betavariate(alpha, beta));
    }

    private static double round4(double v) {
        return Math.round(v * 1.0e4) / 1.0e4;
    }
}
