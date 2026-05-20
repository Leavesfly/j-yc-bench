package com.collinear.ycbench.core;

import com.collinear.ycbench.db.JdbcUtils;
import com.collinear.ycbench.db.dao.EventDao;
import com.collinear.ycbench.db.model.EventType;
import com.collinear.ycbench.db.model.SimEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 插入 / 去重 / 获取模拟事件。镜像 {@code core/events.py}。
 *
 * <p>事件 ID 是确定性的（{@code UUID v5}-风格的 sha-1，基于
 * {@code companyId|type|scheduledAt|dedupeKey|payloadJsonSorted}）。相同种子的重放
 * 因此会产生字节级相同的事件。
 */
public final class EventOps {

    /** Python 的 {@code uuid5(NAMESPACE_URL, ...)} 使用的命名空间 UUID。 */
    private static final UUID NAMESPACE_URL = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");

    private EventOps() {
    }

    /** 插入一个带有可选幂等去重键的事件。返回写入的行。 */
    public static SimEvent insertEvent(Connection db, UUID companyId, EventType type,
                                       OffsetDateTime scheduledAt, Map<String, Object> payload,
                                       String dedupeKey) throws SQLException {
        if (dedupeKey != null) {
            SimEvent existing = EventDao.findUnconsumedByDedupeKey(db, companyId, dedupeKey);
            if (existing != null) return existing;
        }
        SimEvent ev = new SimEvent();
        ev.id = deterministicEventId(companyId, type, scheduledAt, dedupeKey, payload);
        ev.companyId = companyId;
        ev.eventType = type;
        ev.scheduledAt = scheduledAt;
        ev.payload = payload;
        ev.dedupeKey = dedupeKey;
        ev.consumed = false;
        EventDao.insert(db, ev);
        return ev;
    }

    public static void consumeEvent(Connection db, SimEvent ev) throws SQLException {
        EventDao.markConsumed(db, ev.id);
    }

    public static SimEvent fetchNextEvent(Connection db, UUID companyId, OffsetDateTime upTo) throws SQLException {
        return EventDao.fetchNext(db, companyId, upTo);
    }

    // ------------------------------------------------------------------

    private static UUID deterministicEventId(UUID companyId, EventType type,
                                             OffsetDateTime scheduledAt, String dedupeKey,
                                             Map<String, Object> payload) {
        String payloadKey;
        try {
            // 对键进行排序以实现稳定的哈希（匹配 json.dumps(sort_keys=True)）。
            Map<String, Object> sorted = new TreeMap<>(payload == null ? Map.of() : payload);
            payloadKey = JdbcUtils.JSON.writeValueAsString(sorted);
        } catch (Exception ex) {
            throw new IllegalStateException("payload not JSON-serializable", ex);
        }
        String base = companyId
                + "|" + type.value
                + "|" + scheduledAt.toString()
                + "|" + (dedupeKey == null ? "" : dedupeKey)
                + "|" + payloadKey;
        return uuid5(NAMESPACE_URL, base);
    }

    /** 基于名称的 UUID v5（SHA-1）——与 Python 的 {@code uuid.uuid5} 相同的算法。 */
    private static UUID uuid5(UUID namespace, String name) {
        byte[] nsBytes = toBytes(namespace);
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[nsBytes.length + nameBytes.length];
        System.arraycopy(nsBytes, 0, combined, 0, nsBytes.length);
        System.arraycopy(nameBytes, 0, combined, nsBytes.length, nameBytes.length);

        byte[] hash;
        try {
            hash = MessageDigest.getInstance("SHA-1").digest(combined);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-1 unavailable", ex);
        }
        byte[] data = new byte[16];
        System.arraycopy(hash, 0, data, 0, 16);
        data[6] &= 0x0F;
        data[6] |= 0x50;     // version 5
        data[8] &= 0x3F;
        data[8] |= (byte) 0x80;     // RFC4122 variant

        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) msb = (msb << 8) | (data[i] & 0xFF);
        for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (data[i] & 0xFF);
        return new UUID(msb, lsb);
    }

    private static byte[] toBytes(UUID u) {
        byte[] out = new byte[16];
        long msb = u.getMostSignificantBits();
        long lsb = u.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) out[i] = (byte) (msb >>> (56 - 8 * i));
        for (int i = 0; i < 8; i++) out[8 + i] = (byte) (lsb >>> (56 - 8 * i));
        return out;
    }
}
