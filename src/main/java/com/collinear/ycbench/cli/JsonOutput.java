package com.collinear.ycbench.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 每个 CLI 子命令使用的标准输出 JSON 辅助工具。镜像 {@code cli/_output.py}。
 *
 * <p>Python CLI 是契约驱动的——每个命令在 stdout 上打印一个 JSON 文档。
 * 我们匹配该契约：成功 → stdout 上的对象/数组，失败 → stdout 上的 {"error":"..."} 
 * 和非零退出码。
 */
public final class JsonOutput {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private JsonOutput() {
    }

    public static int ok(Object value) {
        return write(System.out, value, 0);
    }

    public static int err(String message) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("error", message);
        return write(System.out, doc, 1);
    }

    private static int write(PrintStream out, Object value, int code) {
        try {
            out.println(MAPPER.writeValueAsString(value));
            return code;
        } catch (Exception ex) {
            System.err.println("JsonOutput: failed to serialize value: " + ex.getMessage());
            return 1;
        }
    }
}
