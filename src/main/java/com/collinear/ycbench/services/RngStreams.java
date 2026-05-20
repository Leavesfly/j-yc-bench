package com.collinear.ycbench.services;

import com.collinear.ycbench.config.PyRandom;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 确定性随机数流。与 {@code services/rng.py:RngStreams} 字节级等价。
 *
 * <p>对于给定的 {@code runSeed}，使用相同的 {@code streamKey} 调用 {@link #stream(String)}
 * 总是会产生从相同字节开始的 {@link PyRandom}。不同的流键在统计上是独立的。
 *
 * <p>种子派生自 {@code sha256("<runSeed>:<streamKey>")[0..8]}，解释为无符号大端 64 位整数——
 * 与 Python 端的构造完全相同。生成的整数被馈送到 {@link PyRandom#seed(long)}，后者对
 * {@code |seed|} 32 位字分解执行 CPython 的确切 {@code init_by_array} 例程。
 */
public final class RngStreams {

    public final long runSeed;

    public RngStreams(long runSeed) {
        this.runSeed = runSeed;
    }

    public PyRandom stream(String streamKey) {
        return new PyRandom(stableSeedBig(runSeed, streamKey));
    }

    /** 与 Python 的 {@code int.from_bytes(d[:8], 'big')} 匹配的无符号 64 位种子值。 */
    static BigInteger stableSeedBig(long runSeed, String streamKey) {
        String raw = runSeed + ":" + streamKey;
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
        byte[] eight = new byte[9];           // leading zero forces positive sign for BigInteger
        System.arraycopy(digest, 0, eight, 1, 8);
        return new BigInteger(eight);
    }

    /** 为测试保留；高位设置时有损。优先使用 {@link #stableSeedBig}。 */
    static long stableSeed(long runSeed, String streamKey) {
        return stableSeedBig(runSeed, streamKey).longValue();
    }
}
