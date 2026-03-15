package org.backendbridge.repo;

import com.fasterxml.jackson.databind.JsonNode;
import org.backendbridge.Db;
import org.backendbridge.LiveBus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Persists online/offline presence reported by the game server.
 */
public final class PresenceRepository {

    private final Db db;

    public PresenceRepository(Db db) {
        this.db = db;
    }

    public void upsertPresence(String serverKey, JsonNode rootOrPlayersArray) throws Exception {
        if (rootOrPlayersArray == null || rootOrPlayersArray.isNull()) return;

        final boolean snapshotMode;
        final JsonNode playersArray;

        if (rootOrPlayersArray.isArray()) {
            snapshotMode = false;
            playersArray = rootOrPlayersArray;
        } else {
            snapshotMode = boolVal(rootOrPlayersArray, "snapshot", false);
            playersArray = rootOrPlayersArray.get("players");
        }

        if (playersArray == null || !playersArray.isArray()) return;

        String sk = (serverKey == null || serverKey.isBlank()) ? null : serverKey.trim();
        Set<String> snapshotOnlineXuIds = snapshotMode ? new HashSet<>() : Set.of();

        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                for (JsonNode p : playersArray) {
                    String xuid = text(p, "xuid");
                    if (xuid == null || xuid.isBlank()) continue;

                    String name = text(p, "name");
                    String ip = text(p, "ip");
                    String hwid = text(p, "hwid");

                    boolean online = snapshotMode
                            ? boolVal(p, "online", true)
                            : boolVal(p, "online", false);

                    upsertPresenceOne(c, xuid.trim(), name, online, ip, hwid, sk);

                    if (snapshotMode && online) snapshotOnlineXuIds.add(xuid.trim());
                }

                if (snapshotMode && sk != null) {
                    markAllOthersOffline(c, sk, snapshotOnlineXuIds);
                }

                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }

        LiveBus.publishInvalidate("players");
    }

    /**
     * Safety net:
     * - stale player updates are cleared
     * - stale servers/clients clear all assigned online players too
     */
    public void markStaleOffline(Duration staleAfter) throws Exception {
        if (staleAfter == null) return;
        long seconds = Math.max(1L, staleAfter.toSeconds());

        int changed = 0;

        try (Connection c = db.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE players " +
                            "SET online=0, online_updated_at=CURRENT_TIMESTAMP(3), online_server_key=NULL " +
                            "WHERE online=1 " +
                            "AND (online_updated_at IS NULL OR online_updated_at < (CURRENT_TIMESTAMP(3) - INTERVAL ? SECOND))"
            )) {
                ps.setLong(1, seconds);
                changed += ps.executeUpdate();
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE players p " +
                            "JOIN servers s ON s.server_key = p.online_server_key " +
                            "SET p.online=0, p.online_updated_at=CURRENT_TIMESTAMP(3), p.online_server_key=NULL " +
                            "WHERE p.online=1 " +
                            "AND (s.last_seen_at IS NULL OR s.last_seen_at < (CURRENT_TIMESTAMP(3) - INTERVAL ? SECOND))"
            )) {
                ps.setLong(1, seconds);
                changed += ps.executeUpdate();
            }
        }

        if (changed > 0) {
            LiveBus.publishInvalidate("players");
        }
    }

    private void upsertPresenceOne(Connection c, String xuid, String name, boolean online, String ip, String hwid, String serverKey) throws Exception {
        String safeName = (name == null || name.isBlank()) ? "Unknown" : name.trim();

        if (online) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO players(" +
                            "xuid, last_name, last_seen_at, online, online_updated_at, online_server_key, last_ip, last_hwid" +
                            ") VALUES(?, ?, CURRENT_TIMESTAMP(3), 1, CURRENT_TIMESTAMP(3), ?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE " +
                            "last_name=VALUES(last_name), " +
                            "last_seen_at=CURRENT_TIMESTAMP(3), " +
                            "online=1, " +
                            "online_updated_at=CURRENT_TIMESTAMP(3), " +
                            "online_server_key=VALUES(online_server_key), " +
                            "last_ip=COALESCE(VALUES(last_ip), players.last_ip), " +
                            "last_hwid=COALESCE(VALUES(last_hwid), players.last_hwid)"
            )) {
                ps.setString(1, xuid);
                ps.setString(2, safeName);
                ps.setString(3, serverKey);
                ps.setObject(4, (ip == null || ip.isBlank()) ? null : ip.trim());
                ps.setObject(5, (hwid == null || hwid.isBlank()) ? null : hwid.trim());
                ps.executeUpdate();
            }
            return;
        }

        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO players(" +
                        "xuid, last_name, last_seen_at, online, online_updated_at, online_server_key, last_ip, last_hwid" +
                        ") VALUES(?, ?, CURRENT_TIMESTAMP(3), 0, CURRENT_TIMESTAMP(3), NULL, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE " +
                        "last_name=COALESCE(VALUES(last_name), players.last_name), " +
                        "online=0, " +
                        "online_updated_at=CURRENT_TIMESTAMP(3), " +
                        "online_server_key=NULL, " +
                        "last_ip=COALESCE(VALUES(last_ip), players.last_ip), " +
                        "last_hwid=COALESCE(VALUES(last_hwid), players.last_hwid)"
        )) {
            ps.setString(1, xuid);
            ps.setString(2, safeName);
            ps.setObject(3, (ip == null || ip.isBlank()) ? null : ip.trim());
            ps.setObject(4, (hwid == null || hwid.isBlank()) ? null : hwid.trim());
            ps.executeUpdate();
        }
    }

    private void markAllOthersOffline(Connection c, String serverKey, Set<String> onlineXuIds) throws Exception {
        if (serverKey == null || serverKey.isBlank()) return;

        if (onlineXuIds == null || onlineXuIds.isEmpty()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE players " +
                            "SET online=0, online_updated_at=CURRENT_TIMESTAMP(3), online_server_key=NULL " +
                            "WHERE online=1 AND online_server_key=?"
            )) {
                ps.setString(1, serverKey);
                ps.executeUpdate();
            }
            return;
        }

        List<String> ids = new ArrayList<>(onlineXuIds);
        int cap = Math.min(ids.size(), 2000);

        StringBuilder sb = new StringBuilder(128 + cap * 2);
        sb.append("UPDATE players SET online=0, online_updated_at=CURRENT_TIMESTAMP(3), online_server_key=NULL ")
                .append("WHERE online=1 AND online_server_key=? AND xuid NOT IN (");
        for (int i = 0; i < cap; i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        sb.append(')');

        try (PreparedStatement ps = c.prepareStatement(sb.toString())) {
            int idx = 1;
            ps.setString(idx++, serverKey);
            for (int i = 0; i < cap; i++) ps.setString(idx++, ids.get(i));
            ps.executeUpdate();
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = (n == null) ? null : n.get(field);
        return (v == null || v.isNull()) ? null : v.asText(null);
    }

    private static boolean boolVal(JsonNode n, String field, boolean defaultValue) {
        JsonNode v = (n == null) ? null : n.get(field);
        if (v == null || v.isNull()) return defaultValue;
        if (v.isBoolean()) return v.asBoolean(defaultValue);
        if (v.isNumber()) return v.asInt(0) != 0;
        String s = v.asText("");
        if (s.isBlank()) return defaultValue;
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
    }
}