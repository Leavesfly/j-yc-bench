package com.collinear.ycbench.agent.runtime;

import com.collinear.ycbench.agent.CommandExecutor;
import com.collinear.ycbench.agent.Prompt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 基于 HTTP 的 LLM 运行时。镜像 {@code agent/runtime/litellm_runtime.py} 的
 * OpenAI 兼容代码路径（chat.completions + function calling）。
 *
 * <p>提供者路由与 Python 端的 LiteLLM 模型前缀行为匹配：
 * <ul>
 *   <li>{@code anthropic/claude-...} → Anthropic Messages API ({@code /v1/messages})</li>
 *   <li>{@code openrouter/...} → OpenRouter ({@code /api/v1/chat/completions})</li>
 *   <li>{@code gemini/...} 或 {@code google/...} → OpenAI 兼容的 Gemini 端点
 *       ({@code generativelanguage.googleapis.com/v1beta/openai/})</li>
 *   <li>其他 → 原始 OpenAI ({@code /v1/chat/completions})</li>
 * </ul>
 *
 * <p>基础 URL 可通过 {@code OPENAI_BASE_URL} 覆盖。API 密钥从提供者特定的环境变量中读取：
 * {@code ANTHROPIC_API_KEY}、{@code OPENAI_API_KEY}、{@code OPENROUTER_API_KEY}、{@code GEMINI_API_KEY}。
 */
public final class HttpLlmRuntime implements AgentRuntime {

    private static final Logger LOGGER = Logger.getLogger(HttpLlmRuntime.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RuntimeSettings settings;
    private final CommandExecutor commandExecutor;
    private final HttpClient http;
    private final Map<String, Session> sessions = new HashMap<>();

    public HttpLlmRuntime(RuntimeSettings settings, CommandExecutor commandExecutor) {
        this.settings = settings;
        this.commandExecutor = commandExecutor;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    // ------------------------------------------------------------------
    // AgentRuntime
    // ------------------------------------------------------------------

    @Override
    public TurnResult runTurn(TurnRequest request) throws Exception {
        Session session = sessions.computeIfAbsent(request.sessionId, k -> new Session());
        session.scratchpad = request.scratchpad;
        proactiveTruncate(session);
        ObjectNode userMsg = JSON.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", request.userInput);
        session.messages.add(userMsg);

        Exception last = null;
        for (int attempt = 1; attempt <= settings.retryMaxAttempts; attempt++) {
            try {
                return doTurn(session);
            } catch (Exception ex) {
                last = ex;
                LOGGER.warning("Turn attempt " + attempt + " failed: " + ex.getMessage());
                if (attempt == settings.retryMaxAttempts) break;
                try {
                    Thread.sleep((long) (settings.retryBackoffSeconds * 1000L * Math.pow(2, attempt - 1)));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("interrupted", ie);
                }
            }
        }
        throw new RuntimeException("runTurn failed after " + settings.retryMaxAttempts + " attempts", last);
    }

    @Override
    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
    }

    @Override
    public void saveSessionMessages(String sessionId, Path path) {
        Session s = sessions.get(sessionId);
        if (s == null) return;
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, JSON.writeValueAsBytes(s.messages));
        } catch (Exception ex) {
            LOGGER.warning("save session failed: " + ex.getMessage());
        }
    }

    @Override
    public int restoreSessionMessages(String sessionId, Path path) {
        if (!Files.exists(path)) return 0;
        try {
            JsonNode arr = JSON.readTree(Files.readAllBytes(path));
            if (!arr.isArray()) return 0;
            Session s = sessions.computeIfAbsent(sessionId, k -> new Session());
            s.messages.clear();
            for (JsonNode n : arr) s.messages.add((ObjectNode) n);
            return s.messages.size();
        } catch (Exception ex) {
            LOGGER.warning("restore session failed: " + ex.getMessage());
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // 核心轮次循环（OpenAI 兼容的 chat.completions）
    // ------------------------------------------------------------------

    private TurnResult doTurn(Session session) throws Exception {
        String model = settings.model;
        if (model.startsWith("anthropic/")) {
            return doAnthropicTurn(session);
        }

        String systemPrompt = effectiveSystemPrompt(session);

        ArrayNode messages = JSON.createArrayNode();
        ObjectNode sysMsg = JSON.createObjectNode();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);
        messages.add(sysMsg);
        for (ObjectNode m : session.messages) messages.add(m);

        ObjectNode body = JSON.createObjectNode();
        body.put("model", stripProviderPrefix(model));
        body.set("messages", messages);
        body.set("tools", buildOpenAiTools());
        body.put("tool_choice", "auto");
        body.put("temperature", settings.temperature);
        body.put("top_p", settings.topP);

        JsonNode response = httpPostJson(openAiEndpoint(model), openAiAuth(model), body);

        JsonNode choice = response.path("choices").path(0);
        JsonNode message = choice.path("message");
        JsonNode usage = response.path("usage");
        int promptTokens = usage.path("prompt_tokens").asInt(0);
        int completionTokens = usage.path("completion_tokens").asInt(0);
        double cost = 0.0;     // 原始 OpenAI 不返回成本；LiteLLM 会返回

        JsonNode toolCalls = message.path("tool_calls");
        List<Map<String, Object>> toolCallsMade = new ArrayList<>();
        Map<String, Object> resumePayload = null;
        String finalOutput;

        if (toolCalls.isArray() && toolCalls.size() > 0) {
            ObjectNode asst = JSON.createObjectNode();
            asst.put("role", "assistant");
            asst.set("content", message.path("content"));
            asst.set("tool_calls", toolCalls.deepCopy());
            session.messages.add(asst);

            List<String> cmdsForOutput = new ArrayList<>();
            for (JsonNode tc : toolCalls) {
                String id = tc.path("id").asText();
                String argsJson = tc.path("function").path("arguments").asText("");
                String command = "";
                try {
                    JsonNode args = JSON.readTree(argsJson);
                    command = args.path("command").asText("");
                } catch (Exception ignored) {
                }
                CommandExecutor.Result r = commandExecutor.run(command);
                String toolResultStr = JSON.writeValueAsString(r.toMap());

                Map<String, Object> tcInfo = new LinkedHashMap<>();
                tcInfo.put("command", command);
                tcInfo.put("result", toolResultStr);
                toolCallsMade.add(tcInfo);
                cmdsForOutput.add(command);

                if (command.startsWith("yc-bench sim resume")) {
                    try {
                        String stdout = r.stdout == null ? "" : r.stdout.trim();
                        if (!stdout.isEmpty()) {
                            JsonNode payload = JSON.readTree(stdout);
                            if (payload.isObject()) {
                                resumePayload = JSON.convertValue(payload, Map.class);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }

                ObjectNode toolMsg = JSON.createObjectNode();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", id);
                toolMsg.put("content", toolResultStr);
                session.messages.add(toolMsg);
            }
            finalOutput = "Executed " + toolCalls.size() + " tool call(s): " + String.join(", ", cmdsForOutput);
        } else {
            String content = message.path("content").asText("");
            ObjectNode asst = JSON.createObjectNode();
            asst.put("role", "assistant");
            asst.put("content", content);
            session.messages.add(asst);
            finalOutput = content;
        }

        return new TurnResult(finalOutput, toolCallsMade, resumePayload != null, resumePayload,
                cost, promptTokens, completionTokens);
    }

    // ------------------------------------------------------------------
    // Anthropic Messages API
    // ------------------------------------------------------------------

    private TurnResult doAnthropicTurn(Session session) throws Exception {
        String systemPrompt = effectiveSystemPrompt(session);
        String anthropicModel = stripProviderPrefix(settings.model);

        // Anthropic 将 system 分离出来；消息必须是交替的 user/assistant。
        ArrayNode messages = JSON.createArrayNode();
        for (ObjectNode m : session.messages) {
            messages.add(anthropicizeMessage(m));
        }

        ObjectNode body = JSON.createObjectNode();
        body.put("model", anthropicModel);
        body.put("system", systemPrompt);
        body.set("messages", messages);
        body.set("tools", buildAnthropicTools());
        body.put("max_tokens", 4096);
        body.put("temperature", settings.temperature);
        body.put("top_p", settings.topP);

        Map<String, String> headers = new LinkedHashMap<>();
        String key = System.getenv("ANTHROPIC_API_KEY");
        if (key == null) throw new IllegalStateException("ANTHROPIC_API_KEY not set");
        headers.put("x-api-key", key);
        headers.put("anthropic-version", "2023-06-01");

        String baseUrl = System.getenv().getOrDefault("ANTHROPIC_BASE_URL", "https://api.anthropic.com");
        JsonNode response = httpPostJson(baseUrl + "/v1/messages", headers, body);

        JsonNode usage = response.path("usage");
        int promptTokens = usage.path("input_tokens").asInt(0);
        int completionTokens = usage.path("output_tokens").asInt(0);

        // 遍历内容块。
        List<Map<String, Object>> toolCallsMade = new ArrayList<>();
        Map<String, Object> resumePayload = null;
        StringBuilder textBuilder = new StringBuilder();
        ArrayNode toolUseBlocks = JSON.createArrayNode();
        ArrayNode toolResultBlocks = JSON.createArrayNode();

        for (JsonNode block : response.path("content")) {
            String type = block.path("type").asText();
            if ("text".equals(type)) {
                textBuilder.append(block.path("text").asText());
            } else if ("tool_use".equals(type)) {
                toolUseBlocks.add(block);
                String id = block.path("id").asText();
                String command = block.path("input").path("command").asText("");
                CommandExecutor.Result r = commandExecutor.run(command);
                String toolResultStr = JSON.writeValueAsString(r.toMap());

                Map<String, Object> tcInfo = new LinkedHashMap<>();
                tcInfo.put("command", command);
                tcInfo.put("result", toolResultStr);
                toolCallsMade.add(tcInfo);

                if (command.startsWith("yc-bench sim resume")) {
                    try {
                        String stdout = r.stdout == null ? "" : r.stdout.trim();
                        if (!stdout.isEmpty()) {
                            JsonNode payload = JSON.readTree(stdout);
                            if (payload.isObject()) resumePayload = JSON.convertValue(payload, Map.class);
                        }
                    } catch (Exception ignored) {
                    }
                }

                ObjectNode trBlock = JSON.createObjectNode();
                trBlock.put("type", "tool_result");
                trBlock.put("tool_use_id", id);
                trBlock.put("content", toolResultStr);
                toolResultBlocks.add(trBlock);
            }
        }

        // 以规范格式记录 assistant 轮次（和 tool_result 后续）。
        ObjectNode asst = JSON.createObjectNode();
        asst.put("role", "assistant");
        asst.set("content", response.path("content").deepCopy());
        session.messages.add(asst);

        if (toolResultBlocks.size() > 0) {
            ObjectNode userToolMsg = JSON.createObjectNode();
            userToolMsg.put("role", "user");
            userToolMsg.set("content", toolResultBlocks);
            session.messages.add(userToolMsg);
        }

        String finalOutput = toolCallsMade.isEmpty()
                ? textBuilder.toString()
                : "Executed " + toolCallsMade.size() + " tool call(s)";

        return new TurnResult(finalOutput, toolCallsMade, resumePayload != null, resumePayload,
                0.0, promptTokens, completionTokens);
    }

    private static ObjectNode anthropicizeMessage(ObjectNode m) {
        // 透传已由 Anthropic 分支生成的消息；如果 slipped in，则将 OpenAI 风格的 {role:tool,...} 转换为 Anthropic tool_result。
        if ("tool".equals(m.path("role").asText())) {
            ObjectNode out = JSON.createObjectNode();
            out.put("role", "user");
            ArrayNode arr = out.putArray("content");
            ObjectNode tr = arr.addObject();
            tr.put("type", "tool_result");
            tr.put("tool_use_id", m.path("tool_call_id").asText());
            tr.put("content", m.path("content").asText());
            return out;
        }
        return m;
    }

    private static ArrayNode buildAnthropicTools() {
        ArrayNode arr = JSON.createArrayNode();
        ObjectNode tool = arr.addObject();
        tool.put("name", "run_command");
        tool.put("description",
                "Execute one benchmark CLI command inside the sandbox and return structured execution output.");
        ObjectNode schema = tool.putObject("input_schema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode cmd = props.putObject("command");
        cmd.put("type", "string");
        cmd.put("description", "The full yc-bench CLI command to execute.");
        schema.putArray("required").add("command");
        return arr;
    }

    // ------------------------------------------------------------------
    // OpenAI 工具模式
    // ------------------------------------------------------------------

    private static ArrayNode buildOpenAiTools() {
        ArrayNode arr = JSON.createArrayNode();
        ObjectNode tool = arr.addObject();
        tool.put("type", "function");
        ObjectNode fn = tool.putObject("function");
        fn.put("name", "run_command");
        fn.put("description",
                "Execute one benchmark CLI command inside the sandbox and return structured execution output.");
        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        ObjectNode cmd = props.putObject("command");
        cmd.put("type", "string");
        cmd.put("description", "The full yc-bench CLI command to execute.");
        params.putArray("required").add("command");
        return arr;
    }

    // ------------------------------------------------------------------
    // 提供者路由辅助方法
    // ------------------------------------------------------------------

    private static String stripProviderPrefix(String model) {
        int slash = model.indexOf('/');
        if (slash < 0) return model;
        String p = model.substring(0, slash);
        if (p.equals("openrouter")) return model.substring(slash + 1);
        if (p.equals("anthropic")) return model.substring(slash + 1);
        if (p.equals("gemini") || p.equals("google")) return model.substring(slash + 1);
        return model;     // openai/foo → 原样发送
    }

    private static String openAiEndpoint(String model) {
        String env = System.getenv("OPENAI_BASE_URL");
        if (env != null && !env.isEmpty()) return env + "/chat/completions";
        if (model.startsWith("openrouter/")) {
            return "https://openrouter.ai/api/v1/chat/completions";
        }
        if (model.startsWith("gemini/") || model.startsWith("google/")) {
            return "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";
        }
        return "https://api.openai.com/v1/chat/completions";
    }

    private static Map<String, String> openAiAuth(String model) {
        Map<String, String> h = new LinkedHashMap<>();
        String key;
        if (model.startsWith("openrouter/")) {
            key = firstNonEmpty(System.getenv("OPENROUTER_API_KEY"), System.getenv("OPENAI_API_KEY"));
        } else if (model.startsWith("gemini/") || model.startsWith("google/")) {
            key = firstNonEmpty(System.getenv("GEMINI_API_KEY"), System.getenv("OPENAI_API_KEY"));
        } else {
            key = System.getenv("OPENAI_API_KEY");
        }
        if (key == null) throw new IllegalStateException("API key not found for model: " + model);
        h.put("Authorization", "Bearer " + key);
        return h;
    }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) if (v != null && !v.isEmpty()) return v;
        return null;
    }

    private String effectiveSystemPrompt(Session session) {
        String base = settings.systemPromptOverride != null ? settings.systemPromptOverride : Prompt.SYSTEM_PROMPT;
        if (session.scratchpad != null && !session.scratchpad.isEmpty()) {
            return base + "\n\n## Your Scratchpad Notes\n" + session.scratchpad;
        }
        return base;
    }

    // ------------------------------------------------------------------
    // HTTP 管道
    // ------------------------------------------------------------------

    private JsonNode httpPostJson(String url, Map<String, String> headers, ObjectNode body) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds((long) settings.requestTimeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8));
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) rb.header(e.getKey(), e.getValue());
        }
        HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("LLM API error " + resp.statusCode() + ": " + resp.body());
        }
        return JSON.readTree(resp.body());
    }

    // ------------------------------------------------------------------
    // 截断（镜像 Python 的 _proactive_truncate）
    // ------------------------------------------------------------------

    private void proactiveTruncate(Session session) {
        List<Integer> userIdx = new ArrayList<>();
        for (int i = 0; i < session.messages.size(); i++) {
            if ("user".equals(session.messages.get(i).path("role").asText())) userIdx.add(i);
        }
        if (userIdx.size() <= settings.historyKeepRounds) return;
        int cutoff = userIdx.get(userIdx.size() - settings.historyKeepRounds);
        List<ObjectNode> kept = new ArrayList<>();
        ObjectNode marker = JSON.createObjectNode();
        marker.put("role", "user");
        marker.put("content", "[Earlier turns removed. Only the last " + settings.historyKeepRounds
                + " turns are retained in this context window.]");
        kept.add(marker);
        kept.addAll(session.messages.subList(cutoff, session.messages.size()));
        session.messages.clear();
        session.messages.addAll(kept);
    }

    private static final class Session {
        final List<ObjectNode> messages = new ArrayList<>();
        String scratchpad;
    }
}
