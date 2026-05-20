package com.collinear.ycbench.web;

import com.collinear.ycbench.db.Database;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * j-yc-bench Web 交互服务器。
 * 提供 REST API + WebSocket 实时推送 + 静态前端页面。
 */
public final class WebServer {

    private static final Logger LOG = LoggerFactory.getLogger(WebServer.class);
    private static final Set<WsContext> WS_CLIENTS = ConcurrentHashMap.newKeySet();
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private WebServer() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static Set<WsContext> wsClients() {
        return WS_CLIENTS;
    }

    /** 广播 JSON 消息到所有 WebSocket 客户端 */
    public static void broadcast(Object message) {
        try {
            String json = MAPPER.writeValueAsString(message);
            WS_CLIENTS.removeIf(ctx -> {
                try {
                    ctx.send(json);
                    return false;
                } catch (Exception e) {
                    return true;
                }
            });
        } catch (Exception e) {
            LOG.warn("Broadcast failed", e);
        }
    }

    /**
     * 启动 Web 服务器。
     * @param port 监听端口
     * @param dbUrl SQLite JDBC URL（如 jdbc:sqlite:db/default_1_ollama_qwen3.5_4b.db）
     */
    public static Javalin start(int port, String dbUrl) {
        // 设置数据库 URL
        System.setProperty("DATABASE_URL", dbUrl);

        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("/static", Location.CLASSPATH);
            config.plugins.enableCors(cors -> cors.add(it -> it.anyHost()));
        });

        // WebSocket
        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                LOG.info("WebSocket client connected");
                WS_CLIENTS.add(ctx);
            });
            ws.onClose(ctx -> {
                WS_CLIENTS.remove(ctx);
                LOG.info("WebSocket client disconnected");
            });
            ws.onError(ctx -> WS_CLIENTS.remove(ctx));
        });

        // REST API routes
        SimulationApi.register(app);
        CompanyApi.register(app);
        EmployeeApi.register(app);
        TaskApi.register(app);
        ClientApi.register(app);
        FinanceApi.register(app);
        SimulationController.register(app);

        app.start(port);
        LOG.info("🌐 Web UI available at http://localhost:{}", port);
        return app;
    }

    /** 获取数据库连接 */
    public static Connection openDb() throws Exception {
        Connection conn = Database.open();
        conn.setAutoCommit(true);
        return conn;
    }
}
