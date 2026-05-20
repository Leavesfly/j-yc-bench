package com.collinear.ycbench.config;

/**
 * 从 {@link DistSpec} 进行单次抽样。
 *
 * <p>匹配 {@code config/sampling.py:sample_from_spec}，包括钳位和
 * 四舍五入行为（beta/normal 保留4位小数）。Triangular/uniform 不
 * 进行四舍五入，因此调用者可以根据调用点进行 floor/round。
 */
public final class Sampling {

    private Sampling() {
    }

    public static double sampleFromSpec(PyRandom rng, DistSpec spec) {
        if (spec instanceof DistSpec.Triangular) {
            DistSpec.Triangular t = (DistSpec.Triangular) spec;
            double v = rng.triangular(t.low, t.high, t.mode);
            return clamp(v, t.low, t.high);
        }
        if (spec instanceof DistSpec.Beta) {
            DistSpec.Beta b = (DistSpec.Beta) spec;
            double v = b.scale * rng.betavariate(b.alpha, b.beta);
            return round4(clamp(v, b.low, b.high));
        }
        if (spec instanceof DistSpec.Normal) {
            DistSpec.Normal n = (DistSpec.Normal) spec;
            double v = rng.gauss(n.mean, n.stdev);
            return round4(clamp(v, n.low, n.high));
        }
        if (spec instanceof DistSpec.Uniform) {
            DistSpec.Uniform u = (DistSpec.Uniform) spec;
            return u.low + (u.high - u.low) * rng.random();
        }
        if (spec instanceof DistSpec.Constant) {
            return ((DistSpec.Constant) spec).value;
        }
        throw new IllegalArgumentException("Unknown DistSpec subclass: " + spec.getClass().getName());
    }

    static double clamp(double v, double lo, double hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
