package com.collinear.ycbench.config;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 分布家族的标记联合类型，镜像 {@code config/sampling.py}。
 *
 * <p>{@code type} 判别器从 YAML 预设中读取，并在反序列化时选择具体的子类。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = DistSpec.Triangular.class, name = "triangular"),
        @JsonSubTypes.Type(value = DistSpec.Beta.class, name = "beta"),
        @JsonSubTypes.Type(value = DistSpec.Normal.class, name = "normal"),
        @JsonSubTypes.Type(value = DistSpec.Uniform.class, name = "uniform"),
        @JsonSubTypes.Type(value = DistSpec.Constant.class, name = "constant"),
})
public abstract class DistSpec {

    public static final class Triangular extends DistSpec {
        public double low;
        public double high;
        public double mode;
    }

    public static final class Beta extends DistSpec {
        public double alpha;
        public double beta;
        public double scale = 1.0;
        public double low = 0.0;
        public double high = 1.0;
    }

    public static final class Normal extends DistSpec {
        public double mean;
        public double stdev;
        public double low;
        public double high;
    }

    public static final class Uniform extends DistSpec {
        public double low;
        public double high;
    }

    public static final class Constant extends DistSpec {
        public double value;
    }
}
