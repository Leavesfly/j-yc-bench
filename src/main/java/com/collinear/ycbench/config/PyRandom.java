package com.collinear.ycbench.config;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * CPython {@code random.Random} 的逐字节移植版本。
 *
 * <p>结合了 C 级别的 Mersenne Twister（MT19937，参见 CPython
 * {@code _randommodule.c}）和 {@code Lib/random.py} 中的纯 Python 算法。
 * 对于给定的整数种子，每次调用序列
 * ({@link #random()}, {@link #randint(long, long)}, {@link #uniform(double, double)},
 * {@link #triangular(double, double, double)}, {@link #sample(List, int)},
 * {@link #shuffle(List)}, {@link #choice(List)}, {@link #gauss(double, double)},
 * {@link #gammavariate(double, double)}, {@link #betavariate(double, double)})
 * 都与 CPython 完全匹配。
 *
 * <p>接受 {@link java.util.Random} 的静态 {@code triangular/uniform/sample/shuffle/choice/gauss/betaVariate/gammaVariate}
 * 辅助方法作为薄层跳板保留下来，以便现有调用点能够编译，但它们将输入包装在 {@link PyRandom} 克隆中——
 * 这意味着它们不应在新代码中使用；请优先使用实例方法。
 */
public final class PyRandom {

    // ---------------------------------------------------------------
    // MT19937 状态 — 匹配 CPython _randommodule.c。
    // ---------------------------------------------------------------

    private static final int N = 624;
    private static final int M = 397;
    private static final int MATRIX_A = 0x9908b0df;
    private static final int UPPER_MASK = 0x80000000;
    private static final int LOWER_MASK = 0x7fffffff;

    private final int[] mt = new int[N];
    private int index = N + 1;

    /** Last gauss() leftover (Python keeps state between calls). */
    private Double gaussNext = null;

    public PyRandom() {
    /** CPython 的默认种子来自 os.urandom；我们需要显式种子。*/
        seed(0L);
    }

    public PyRandom(long seed) {
        seed(seed);
    }

    public PyRandom(BigInteger seed) {
        seed(seed);
    }

    // ---------------------------------------------------------------
    // 种子设置 — 镜像 CPython _randommodule.c 中的 random_seed()。
    // ---------------------------------------------------------------

    public void seed(long seed) {
        // Python 取 |seed|；在 init_by_array 之前丢弃符号。
        BigInteger n = BigInteger.valueOf(seed).abs();
        seed(n);
    }

    public void seed(BigInteger seed) {
        // CPython: init_by_array(key[], length)，其中 key 是 |seed| 的小端
        // 32 位字分解；对于 seed == 0，key = [0]。
        BigInteger n = seed.abs();
        int[] key;
        if (n.signum() == 0) {
            key = new int[]{0};
        } else {
            int bits = n.bitLength();
            int words = (bits + 31) / 32;
            key = new int[words];
            BigInteger mask = BigInteger.valueOf(0xFFFFFFFFL);
            for (int i = 0; i < words; i++) {
                key[i] = n.shiftRight(i * 32).and(mask).intValue();
            }
        }
        initByArray(key);
        gaussNext = null;
    }

    private void initGenrand(int s) {
        mt[0] = s;
        for (int mti = 1; mti < N; mti++) {
            mt[mti] = 1812433253 * (mt[mti - 1] ^ (mt[mti - 1] >>> 30)) + mti;
        }
        index = N;
    }

    private void initByArray(int[] key) {
        initGenrand(19650218);
        int i = 1, j = 0;
        int k = Math.max(N, key.length);
        for (; k != 0; k--) {
            long t = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1664525)) + key[j] + j;
            mt[i] = (int) t;
            i++;
            j++;
            if (i >= N) { mt[0] = mt[N - 1]; i = 1; }
            if (j >= key.length) j = 0;
        }
        for (k = N - 1; k != 0; k--) {
            long t = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1566083941)) - i;
            mt[i] = (int) t;
            i++;
            if (i >= N) { mt[0] = mt[N - 1]; i = 1; }
        }
        mt[0] = 0x80000000;
        index = N;
    }

    /** 生成一个 32 位无符号字 — CPython {@code genrand_uint32}。*/
    private int genrandUint32() {
        if (index >= N) {
            int kk;
            for (kk = 0; kk < N - M; kk++) {
                int y = (mt[kk] & UPPER_MASK) | (mt[kk + 1] & LOWER_MASK);
                mt[kk] = mt[kk + M] ^ (y >>> 1) ^ ((y & 1) != 0 ? MATRIX_A : 0);
            }
            for (; kk < N - 1; kk++) {
                int y = (mt[kk] & UPPER_MASK) | (mt[kk + 1] & LOWER_MASK);
                mt[kk] = mt[kk + (M - N)] ^ (y >>> 1) ^ ((y & 1) != 0 ? MATRIX_A : 0);
            }
            int y = (mt[N - 1] & UPPER_MASK) | (mt[0] & LOWER_MASK);
            mt[N - 1] = mt[M - 1] ^ (y >>> 1) ^ ((y & 1) != 0 ? MATRIX_A : 0);
            index = 0;
        }
        int y = mt[index++];
        y ^= y >>> 11;
        y ^= (y << 7) & 0x9d2c5680;
        y ^= (y << 15) & 0xefc60000;
        y ^= y >>> 18;
        return y;
    }

    // ---------------------------------------------------------------
    // 核心原语 — 与 CPython 1:1 匹配。
    // ---------------------------------------------------------------

    /** {@code random.random()} — {@code [0.0, 1.0)} 范围内的 53 位 double。*/
    public double random() {
        long a = (genrandUint32() & 0xFFFFFFFFL) >>> 5;   // 27 high bits
        long b = (genrandUint32() & 0xFFFFFFFFL) >>> 6;   // 26 high bits
        return (a * 67108864.0 + b) * (1.0 / 9007199254740992.0);
    }

    /** CPython {@code getrandbits(k)} — 精确位数。*/
    public BigInteger getrandbits(int k) {
        if (k <= 0) throw new IllegalArgumentException("number of bits must be greater than zero");
        // Build little-endian 32-bit words.
        int words = (k + 31) / 32;
        BigInteger result = BigInteger.ZERO;
        for (int i = 0; i < words; i++) {
            long r = genrandUint32() & 0xFFFFFFFFL;
            if (i == words - 1) {
                int tailBits = k - 32 * (words - 1);
                if (tailBits < 32) r >>>= (32 - tailBits);
            }
            result = result.or(BigInteger.valueOf(r).shiftLeft(32 * i));
        }
        return result;
    }

    /** CPython {@code Random._randbelow(n)} — {@code [0, n)} 范围内的均匀分布。*/
    public long randbelow(long n) {
        if (n <= 0) throw new IllegalArgumentException("n must be positive");
        int k = 64 - Long.numberOfLeadingZeros(n);   // bit_length(n)
        while (true) {
            BigInteger r = getrandbits(k);
            long v = r.longValueExact();
            if (v < n) return v;
        }
    }

    /** CPython {@code randint(a, b)} — 两端都包含。*/
    public long randint(long a, long b) {
        if (b < a) throw new IllegalArgumentException("empty range for randint");
        return a + randbelow(b - a + 1);
    }

    public double uniform(double a, double b) {
        return a + (b - a) * random();
    }

    public double triangular(double low, double high, double mode) {
        double u = random();
        double c;
        try {
            c = (mode - low) / (high - low);
        } catch (ArithmeticException ex) {
            return low;
        }
        if (Double.isNaN(c) || Double.isInfinite(c)) return low;
        if (u > c) {
            u = 1.0 - u;
            c = 1.0 - c;
            double tmp = low;
            low = high;
            high = tmp;
        }
        return low + (high - low) * Math.sqrt(u * c);
    }

    /** CPython {@code random.choice(seq)}。*/
    public <T> T choice(List<T> list) {
        if (list.isEmpty()) throw new IllegalArgumentException("Cannot choose from an empty sequence");
        return list.get((int) randbelow(list.size()));
    }

    /** CPython {@code random.shuffle(x)}。*/
    public <T> void shuffle(List<T> list) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = (int) randbelow(i + 1);
            Collections.swap(list, i, j);
        }
    }

    /**
     * CPython {@code random.sample(population, k)} — 使用与 Python 相同的
     * setsize 公式键控的双策略算法（池 vs. 集合）。
     */
    public <T> List<T> sample(List<T> population, int k) {
        int n = population.size();
        if (k < 0 || k > n) throw new IllegalArgumentException("Sample larger than population or is negative");
        List<T> result = new ArrayList<>(k);
        for (int i = 0; i < k; i++) result.add(null);

        int setsize = 21;
        if (k > 5) {
            // CPython:  setsize += 4 ** ceil(log(k * 3, 4))
            double logV = Math.log(k * 3.0) / Math.log(4.0);
            int exp = (int) Math.ceil(logV);
            setsize += (int) Math.pow(4, exp);
        }
        if (n <= setsize) {
            List<T> pool = new ArrayList<>(population);
            for (int i = 0; i < k; i++) {
                int j = (int) randbelow(n - i);
                result.set(i, pool.get(j));
                pool.set(j, pool.get(n - i - 1));
            }
        } else {
            java.util.HashSet<Integer> selected = new java.util.HashSet<>();
            for (int i = 0; i < k; i++) {
                int j = (int) randbelow(n);
                while (selected.contains(j)) {
                    j = (int) randbelow(n);
                }
                selected.add(j);
                result.set(i, population.get(j));
            }
        }
        return result;
    }

    // ---------------------------------------------------------------
    // 连续分布 — 匹配 Lib/random.py。
    // ---------------------------------------------------------------

    private static final double NV_MAGICCONST = 4.0 * Math.exp(-0.5) / Math.sqrt(2.0);
    private static final double SG_MAGICCONST = 1.0 + Math.log(4.5);

    /** CPython {@code gauss(mu, sigma)} — 使用 Marsaglia 极坐标方法并存储剩余值。*/
    public double gauss(double mu, double sigma) {
        double z;
        Double leftover = gaussNext;
        gaussNext = null;
        if (leftover != null) {
            z = leftover;
        } else {
            double x2pi = random() * 2.0 * Math.PI;
            double g2rad = Math.sqrt(-2.0 * Math.log(1.0 - random()));
            z = Math.cos(x2pi) * g2rad;
            gaussNext = Math.sin(x2pi) * g2rad;
        }
        return mu + z * sigma;
    }

    /** CPython {@code gammavariate(alpha, beta)}。*/
    public double gammavariate(double alpha, double beta) {
        if (alpha <= 0.0 || beta <= 0.0)
            throw new IllegalArgumentException("gammavariate: alpha and beta must be > 0");

        if (alpha > 1.0) {
            double ainv = Math.sqrt(2.0 * alpha - 1.0);
            double bbb = alpha - Math.log(4.0);
            double ccc = alpha + ainv;
            while (true) {
                double u1 = random();
                if (!(1e-7 < u1 && u1 < 0.9999999)) continue;
                double u2 = 1.0 - random();
                double v = Math.log(u1 / (1.0 - u1)) / ainv;
                double x = alpha * Math.exp(v);
                double z = u1 * u1 * u2;
                double r = bbb + ccc * v - x;
                if (r + SG_MAGICCONST - 4.5 * z >= 0.0 || r >= Math.log(z)) {
                    return x * beta;
                }
            }
        } else if (alpha == 1.0) {
            return -Math.log(1.0 - random()) * beta;
        } else {
            // alpha < 1: rejection per CPython
            double x;
            double b = (Math.E + alpha) / Math.E;
            while (true) {
                double u = random();
                double p = b * u;
                if (p <= 1.0) {
                    x = Math.pow(p, 1.0 / alpha);
                } else {
                    x = -Math.log((b - p) / alpha);
                }
                double u1 = random();
                if (p > 1.0) {
                    if (u1 <= Math.pow(x, alpha - 1.0)) break;
                } else if (u1 <= Math.exp(-x)) {
                    break;
                }
            }
            return x * beta;
        }
    }

    public double betavariate(double alpha, double beta) {
        double y = gammavariate(alpha, 1.0);
        if (y == 0.0) return 0.0;
        return y / (y + gammavariate(beta, 1.0));
    }

}
