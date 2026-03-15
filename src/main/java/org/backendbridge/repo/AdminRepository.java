package org.backendbridge.repo;

import org.backendbridge.Db;
import org.backendbridge.JsonUtil;
import org.backendbridge.LiveBus;
import org.backendbridge.adminui.AdminPages;
import org.backendbridge.adminui.AdminUiUtil;
import org.backendbridge.adminui.Lang;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AdminRepository {

    private static final int ONLINE_STALE_SECONDS = 90;

    private final Db db;
    private final String serverName;
    private final String broadcastPrefix;
    private final UsersRepository usersRepo;
    private final MetricsRepository metricsRepo;

    public AdminRepository(Db db, String serverName, String broadcastPrefix, UsersRepository usersRepo, MetricsRepository metricsRepo) {
        this.db = db;
        this.serverName = (serverName == null || serverName.isBlank()) ? "MyServer" : serverName;
        this.broadcastPrefix = (broadcastPrefix == null) ? "" : broadcastPrefix;
        this.usersRepo = usersRepo;
        this.metricsRepo = metricsRepo;
    }

    public String renderLoginHtml(Lang lang, String err) {
        return AdminPages.login(serverName, lang, err);
    }

    public String renderUsersHtml(Lang lang, String ok, String err) throws Exception {
        return AdminPages.users(
                serverName,
                lang,
                AdminPages.messageBox(lang, ok, err),
                usersRepo.roleOptionsHtml(),
                usersRepo.userOptionsHtmlNonRoot(),
                usersRepo.listUsersHtmlRows()
        );
    }

    public String renderAccountHtml(Lang lang, String ok, String err, String username) {
        return AdminPages.account(serverName, lang, AdminPages.messageBox(lang, ok, err), username);
    }

    public String renderRolesHtml(Lang lang, String ok, String err) throws Exception {
        String msg = AdminPages.messageBox(lang, ok, err);
        List<WebRole> roles = loadRoles();
        List<WebPerm> perms = loadPermissions();
        Map<Long, Map<String, Boolean>> rolePerms = loadRolePermissionsByRoleId();
        return AdminPages.rolesWithPermissions(serverName, lang, msg, roles, perms, rolePerms);
    }

    public String renderPlayersHtml(Lang lang) throws Exception {
        long totalPlayers = count("SELECT COUNT(*) FROM players");
        long playersWithStats = count("SELECT COUNT(*) FROM players p JOIN player_stats s ON s.xuid = p.xuid");
        long totalBans = count("SELECT COUNT(*) FROM bans");
        long activeBans = count("SELECT COUNT(*) FROM bans b WHERE b.revoked_at IS NULL AND (b.expires_at IS NULL OR b.expires_at > CURRENT_TIMESTAMP(3))");

        String sql =
                "SELECT p.xuid, p.last_name, p.last_seen_at, " +
                        "CASE WHEN p.online = 1 AND p.online_updated_at IS NOT NULL " +
                        "AND p.online_updated_at >= (CURRENT_TIMESTAMP(3) - INTERVAL " + ONLINE_STALE_SECONDS + " SECOND) THEN 1 ELSE 0 END AS online_effective, " +
                        "p.online_updated_at, p.online_server_key, " +
                        "COALESCE(srv.server_key, '') AS online_server_name, " +
                        "COALESCE(ps.playtime_seconds,0) AS playtime_seconds, " +
                        "COALESCE(ps.kills,0) AS kills, " +
                        "COALESCE(ps.deaths,0) AS deaths, " +
                        "ps.updated_at AS stats_updated_at " +
                        "FROM players p " +
                        "LEFT JOIN player_stats ps ON ps.xuid=p.xuid " +
                        "LEFT JOIN servers srv ON srv.server_key=p.online_server_key " +
                        "ORDER BY online_effective DESC, p.last_seen_at DESC " +
                        "LIMIT 500";

        StringBuilder rows = new StringBuilder(120_000);
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String xuid = rs.getString("xuid");
                String name = rs.getString("last_name");
                boolean online = rs.getInt("online_effective") == 1;

                rows.append(AdminPages.playerRow(
                        xuid,
                        name,
                        online,
                        AdminUiUtil.toIso(rs.getTimestamp("online_updated_at")),
                        rs.getString("online_server_name"),
                        rs.getLong("playtime_seconds"),
                        rs.getLong("kills"),
                        rs.getLong("deaths"),
                        AdminUiUtil.toIso(rs.getTimestamp("last_seen_at")),
                        AdminUiUtil.toIso(rs.getTimestamp("stats_updated_at"))
                ));
            }
        }

        return AdminPages.players(
                serverName,
                lang,
                totalPlayers,
                playersWithStats,
                activeBans,
                totalBans,
                loadServerOptionsHtml(),
                rows.toString()
        );
    }

    public String renderPlayerDetailHtml(Lang lang, String xuid) throws Exception {
        Player p = loadPlayer(xuid);
        if (p == null) return AdminPages.playerNotFound(serverName, lang);

        BanState ban = loadActiveBan(xuid);
        boolean active = ban != null;
        List<BanEvent> events = active ? loadBanEvents(ban.banId) : List.of();

        return AdminPages.playerDetail(
                serverName,
                lang,
                p.xuid,
                p.name,
                p.online,
                p.onlineUpdatedIso,
                p.onlineServerKey,
                p.onlineServerName,
                p.lastSeenIso,
                p.playtimeSeconds,
                p.kills,
                p.deaths,
                active,
                active ? ban.banId : -1,
                active ? ban.reason : "",
                active ? ban.createdAtIso : "",
                active ? ban.expiresAtIso : null,
                active ? ban.updatedAtIso : "",
                events
        );
    }

    public String renderBansHtml(Lang lang) throws Exception {
        long totalPlayers = count("SELECT COUNT(*) FROM players");
        long totalBans = count("SELECT COUNT(*) FROM bans");
        long activeBans = count("SELECT COUNT(*) FROM bans b WHERE b.revoked_at IS NULL AND (b.expires_at IS NULL OR b.expires_at > CURRENT_TIMESTAMP(3))");
        long revokedBans = count("SELECT COUNT(*) FROM bans b WHERE b.revoked_at IS NOT NULL");

        String sql = "SELECT ban_id, xuid, reason, created_at, expires_at, revoked_at, updated_at FROM bans ORDER BY updated_at DESC LIMIT 500";
        StringBuilder rows = new StringBuilder(150_000);
        Instant now = Instant.now();

        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                long banId = rs.getLong("ban_id");
                String bxuid = rs.getString("xuid");
                String reason = rs.getString("reason");

                Timestamp createdTs = rs.getTimestamp("created_at");
                Timestamp expiresTs = rs.getTimestamp("expires_at");
                Timestamp revokedTs = rs.getTimestamp("revoked_at");
                Timestamp updatedTs = rs.getTimestamp("updated_at");

                String createdAt = AdminUiUtil.toIso(createdTs);
                String expiresAt = AdminUiUtil.toIsoNullable(expiresTs);
                String revokedAt = AdminUiUtil.toIsoNullable(revokedTs);
                String updatedAt = AdminUiUtil.toIso(updatedTs);

                boolean isActive = revokedTs == null && (expiresTs == null || expiresTs.toInstant().isAfter(now));
                rows.append(AdminPages.banRow(banId, bxuid, reason, createdAt, expiresAt, revokedAt, updatedAt, isActive));
            }
        }

        return AdminPages.bans(serverName, lang, totalPlayers, activeBans, revokedBans, totalBans, rows.toString());
    }

    public String renderServerStatsHtml(Lang lang, String serverKey) throws Exception {
        String sk = (serverKey == null || serverKey.isBlank()) ? firstServerKey() : serverKey;
        MetricsRepository.Metrics latest = (sk == null) ? null : metricsRepo.loadLatest(sk);
        return AdminPages.serverStats(serverName, lang, sk, latest);
    }

    public String renderAuditHtml(Lang lang, String ok, String err, String actor, String action, String q, Integer limit) throws Exception {
        String msg = AdminPages.messageBox(lang, ok, err);
        int lim = (limit == null) ? 500 : Math.max(50, Math.min(limit, 2000));

        List<AuditEntry> entries = loadAuditEntries(actor, action, q, lim);

        StringBuilder rows = new StringBuilder(120_000);
        for (AuditEntry e : entries) {
            rows.append(AdminPages.auditRow(e.createdAtIso, e.actorUsername, e.actionKey, e.details, e.id));
        }

        String filtersHtml = AdminPages.auditFilterForm(lang, actor, action, q, String.valueOf(lim));
        return AdminPages.audit(serverName, lang, msg, filtersHtml, rows.toString(), entries.size());
    }

    private List<AuditEntry> loadAuditEntries(String actor, String action, String q, int limit) throws Exception {
        String a = (actor == null) ? "" : actor.trim();
        String ak = (action == null) ? "" : action.trim();
        String qq = (q == null) ? "" : q.trim();

        StringBuilder sql = new StringBuilder(512);
        sql.append("SELECT id, actor_username, action_key, details, created_at FROM admin_audit_log WHERE 1=1 ");

        if (!a.isBlank()) sql.append("AND actor_username LIKE ? ");
        if (!ak.isBlank()) sql.append("AND action_key LIKE ? ");
        if (!qq.isBlank()) sql.append("AND details LIKE ? ");

        sql.append("ORDER BY created_at DESC, id DESC LIMIT ").append(Math.max(1, Math.min(limit, 2000)));

        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {

            int idx = 1;
            if (!a.isBlank()) ps.setString(idx++, "%" + a + "%");
            if (!ak.isBlank()) ps.setString(idx++, "%" + ak + "%");
            if (!qq.isBlank()) ps.setString(idx++, "%" + qq + "%");

            try (ResultSet rs = ps.executeQuery()) {
                var out = new java.util.ArrayList<AuditEntry>();
                while (rs.next()) {
                    out.add(new AuditEntry(
                            rs.getLong("id"),
                            rs.getString("actor_username"),
                            rs.getString("action_key"),
                            rs.getString("details"),
                            AdminUiUtil.toIso(rs.getTimestamp("created_at"))
                    ));
                }
                return out;
            }
        }
    }

    private record AuditEntry(long id, String actorUsername, String actionKey, String details, String createdAtIso) {}

    public void createUser(String actorUsername, String username, String rawPassword, long roleId) throws Exception {
        try {
            usersRepo.createUserRaw(username, rawPassword, roleId);
            audit(actorUsername, "users.create", "username=" + safe(username) + ", roleId=" + roleId);
            LiveBus.publishInvalidate("users", "audit");
        } catch (Exception e) {
            audit(actorUsername, "users.create.failed", "username=" + safe(username) + ", reason=" + safe(e.getMessage()));
            throw e;
        }
    }

    public void setUserRole(String actorUsername, String username, long roleId) throws Exception {
        try {
            usersRepo.setUserRole(username, roleId);
            audit(actorUsername, "users.role.set", "username=" + safe(username) + ", roleId=" + roleId);
            LiveBus.publishInvalidate("users", "audit");
        } catch (Exception e) {
            audit(actorUsername, "users.role.set.failed", "username=" + safe(username) + ", reason=" + safe(e.getMessage()));
            throw e;
        }
    }

    public void resetPassword(String actorUsername, String username, String rawPassword) throws Exception {
        try {
            usersRepo.resetPasswordRaw(username, rawPassword);
            audit(actorUsername, "users.password.reset", "username=" + safe(username));
            LiveBus.publishInvalidate("users", "audit");
        } catch (Exception e) {
            audit(actorUsername, "users.password.reset.failed", "username=" + safe(username) + ", reason=" + safe(e.getMessage()));
            throw e;
        }
    }

    public void changeOwnPassword(String username, String currentPassword, String newPassword) throws Exception {
        try {
            usersRepo.changeOwnPassword(username, currentPassword, newPassword);
            audit(username, "account.password.change", "username=" + safe(username));
            LiveBus.publishInvalidate("audit");
        } catch (Exception e) {
            audit(username, "account.password.change.failed", "username=" + safe(username) + ", reason=" + safe(e.getMessage()));
            throw e;
        }
    }

    public void createRole(String actorUsername, String roleKey, String displayName) throws Exception {
        if (roleKey == null || roleKey.isBlank()) throw new IllegalArgumentException("roleKey missing");
        if (displayName == null || displayName.isBlank()) throw new IllegalArgumentException("displayName missing");

        String rk = roleKey.trim().toLowerCase();
        String dn = displayName.trim();

        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO web_roles(role_key, display_name) VALUES(?, ?)")) {
            ps.setString(1, rk);
            ps.setString(2, dn);
            ps.executeUpdate();
        }

        audit(actorUsername, "roles.create", "roleKey=" + safe(rk) + ", displayName=" + safe(dn));
        LiveBus.publishInvalidate("roles", "users", "audit");
    }

    public void setRolePermission(String actorUsername, long roleId, String permKey, boolean enabled) throws Exception {
        if (roleId <= 0) throw new IllegalArgumentException("roleId missing");
        if (permKey == null || permKey.isBlank()) throw new IllegalArgumentException("permKey missing");

        try (Connection c = db.getConnection()) {
            long permId = permIdByKey(c, permKey.trim());
            if (permId <= 0) throw new IllegalArgumentException("permission not found");

            if (enabled) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT IGNORE INTO web_role_permissions(role_id, perm_id) VALUES(?, ?)"
                )) {
                    ps.setLong(1, roleId);
                    ps.setLong(2, permId);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM web_role_permissions WHERE role_id=? AND perm_id=?"
                )) {
                    ps.setLong(1, roleId);
                    ps.setLong(2, permId);
                    ps.executeUpdate();
                }
            }
        }

        audit(actorUsername, "roles.permission.set", "roleId=" + roleId + ", permKey=" + safe(permKey) + ", enabled=" + enabled);
        LiveBus.publishInvalidate("roles", "users", "audit");
    }

    public void banPlayerByXuid(String actorUsername, String xuid, String reason, Integer durationHours) throws Exception {
        if (xuid == null || xuid.isBlank()) throw new IllegalArgumentException("xuid missing");
        if (reason == null || reason.isBlank()) reason = "No reason";

        try (Connection c = db.getConnection()) {
            if (!playerExists(c, xuid)) upsertPlayerStub(c, xuid);
            if (hasActiveBan(c, xuid)) {
                audit(actorUsername, "players.ban.skipped", "xuid=" + safe(xuid) + ", reason=already_active");
                return;
            }

            Timestamp expiresAt = null;
            if (durationHours != null && durationHours > 0) {
                expiresAt = Timestamp.from(Instant.now().plusSeconds(durationHours * 3600L));
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO bans(xuid, reason, created_at, expires_at, revoked_at, updated_at, actor_type, actor_username, actor_server_key) " +
                            "VALUES(?, ?, CURRENT_TIMESTAMP(3), ?, NULL, CURRENT_TIMESTAMP(3), 'WEB', ?, NULL)"
            )) {
                ps.setString(1, xuid);
                ps.setString(2, reason);
                ps.setTimestamp(3, expiresAt);
                ps.setString(4, actorUsername);
                ps.executeUpdate();
            }
        }

        audit(actorUsername, "players.ban", "xuid=" + safe(xuid) + ", hours=" + durationHours + ", reason=" + safe(reason));
        LiveBus.publishInvalidate("bans", "players", "audit");
    }

    public void unbanPlayerByXuid(String actorUsername, String xuid) throws Exception {
        if (xuid == null || xuid.isBlank()) throw new IllegalArgumentException("xuid missing");

        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE bans SET revoked_at=CURRENT_TIMESTAMP(3), updated_at=CURRENT_TIMESTAMP(3) " +
                             "WHERE xuid=? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP(3))"
             )) {
            ps.setString(1, xuid);
            ps.executeUpdate();
        }

        audit(actorUsername, "players.unban", "xuid=" + safe(xuid));
        LiveBus.publishInvalidate("bans", "players", "audit");
    }

    public void kickPlayer(String actorUsername, String serverKey, String xuid, String reason) throws Exception {
        if (serverKey == null || serverKey.isBlank()) throw new IllegalArgumentException("serverKey missing");
        if (xuid == null || xuid.isBlank()) throw new IllegalArgumentException("xuid missing");
        if (reason == null || reason.isBlank()) reason = "Kicked by admin";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("xuid", xuid.trim());
        payload.put("reason", reason.trim());

        queueServerCommand(serverKey.trim(), "KICK", payload);
        audit(actorUsername, "players.kick", "xuid=" + safe(xuid) + ", serverKey=" + safe(serverKey));
    }

    public void messagePlayer(String actorUsername, String serverKey, String xuid, String message) throws Exception {
        if (serverKey == null || serverKey.isBlank()) throw new IllegalArgumentException("serverKey missing");
        if (xuid == null || xuid.isBlank()) throw new IllegalArgumentException("xuid missing");
        if (message == null || message.isBlank()) throw new IllegalArgumentException("message missing");

        String finalMessage = broadcastPrefix + message.trim();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("xuid", xuid.trim());
        payload.put("message", finalMessage);

        queueServerCommand(serverKey.trim(), "MESSAGE", payload);
        audit(actorUsername, "players.message", "xuid=" + safe(xuid) + ", serverKey=" + safe(serverKey));
    }

    public void broadcastMessage(String actorUsername, String serverKeyOrAll, String message) throws Exception {
        if (message == null || message.isBlank()) throw new IllegalArgumentException("message missing");

        String target = (serverKeyOrAll == null || serverKeyOrAll.isBlank()) ? "__all__" : serverKeyOrAll.trim();
        String finalMessage = broadcastPrefix + message.trim();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", finalMessage);

        if ("__all__".equalsIgnoreCase(target)) {
            List<String> serverKeys = loadServerKeys();
            if (serverKeys.isEmpty()) throw new IllegalArgumentException("no servers available");
            for (String serverKey : serverKeys) {
                queueServerCommand(serverKey, "BROADCAST", payload);
            }
            audit(actorUsername, "players.broadcast", "target=all, message=" + safe(message));
            return;
        }

        queueServerCommand(target, "BROADCAST", payload);
        audit(actorUsername, "players.broadcast", "target=" + safe(target) + ", message=" + safe(message));
    }

    public String playersLiveJson() throws Exception {
        long totalPlayers = count("SELECT COUNT(*) FROM players");
        long playersWithStats = count("SELECT COUNT(*) FROM players p JOIN player_stats s ON s.xuid = p.xuid");
        long totalBans = count("SELECT COUNT(*) FROM bans");
        long activeBans = count("SELECT COUNT(*) FROM bans b WHERE b.revoked_at IS NULL AND (b.expires_at IS NULL OR b.expires_at > CURRENT_TIMESTAMP(3))");

        String sql =
                "SELECT p.xuid, p.last_name, p.last_seen_at, " +
                        "CASE WHEN p.online = 1 AND p.online_updated_at IS NOT NULL " +
                        "AND p.online_updated_at >= (CURRENT_TIMESTAMP(3) - INTERVAL " + ONLINE_STALE_SECONDS + " SECOND) THEN 1 ELSE 0 END AS online_effective, " +
                        "p.online_updated_at, p.online_server_key, " +
                        "COALESCE(srv.server_key, '') AS online_server_name, " +
                        "COALESCE(ps.playtime_seconds,0) AS playtime_seconds, " +
                        "COALESCE(ps.kills,0) AS kills, " +
                        "COALESCE(ps.deaths,0) AS deaths, " +
                        "ps.updated_at AS stats_updated_at " +
                        "FROM players p " +
                        "LEFT JOIN player_stats ps ON ps.xuid=p.xuid " +
                        "LEFT JOIN servers srv ON srv.server_key=p.online_server_key " +
                        "ORDER BY online_effective DESC, p.last_seen_at DESC " +
                        "LIMIT 500";

        StringBuilder rows = new StringBuilder(120_000);
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String xuid = rs.getString("xuid");
                String name = rs.getString("last_name");
                boolean online = rs.getInt("online_effective") == 1;

                rows.append(AdminPages.playerRow(
                        xuid,
                        name,
                        online,
                        AdminUiUtil.toIso(rs.getTimestamp("online_updated_at")),
                        rs.getString("online_server_name"),
                        rs.getLong("playtime_seconds"),
                        rs.getLong("kills"),
                        rs.getLong("deaths"),
                        AdminUiUtil.toIso(rs.getTimestamp("last_seen_at")),
                        AdminUiUtil.toIso(rs.getTimestamp("stats_updated_at"))
                ));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalPlayers", totalPlayers);
        out.put("playersWithStats", playersWithStats);
        out.put("activeBans", activeBans);
        out.put("totalBans", totalBans);
        out.put("rowsHtml", rows.toString());
        return JsonUtil.OM.writeValueAsString(out);
    }

    public String bansLiveJson() throws Exception {
        long totalPlayers = count("SELECT COUNT(*) FROM players");
        long totalBans = count("SELECT COUNT(*) FROM bans");
        long activeBans = count("SELECT COUNT(*) FROM bans b WHERE b.revoked_at IS NULL AND (b.expires_at IS NULL OR b.expires_at > CURRENT_TIMESTAMP(3))");
        long revokedBans = count("SELECT COUNT(*) FROM bans b WHERE b.revoked_at IS NOT NULL");

        String sql = "SELECT ban_id, xuid, reason, created_at, expires_at, revoked_at, updated_at FROM bans ORDER BY updated_at DESC LIMIT 500";
        StringBuilder rows = new StringBuilder(150_000);
        Instant now = Instant.now();

        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                long banId = rs.getLong("ban_id");
                String bxuid = rs.getString("xuid");
                String reason = rs.getString("reason");

                Timestamp createdTs = rs.getTimestamp("created_at");
                Timestamp expiresTs = rs.getTimestamp("expires_at");
                Timestamp revokedTs = rs.getTimestamp("revoked_at");
                Timestamp updatedTs = rs.getTimestamp("updated_at");

                String createdAt = AdminUiUtil.toIso(createdTs);
                String expiresAt = AdminUiUtil.toIsoNullable(expiresTs);
                String revokedAt = AdminUiUtil.toIsoNullable(revokedTs);
                String updatedAt = AdminUiUtil.toIso(updatedTs);

                boolean isActive = revokedTs == null && (expiresTs == null || expiresTs.toInstant().isAfter(now));
                rows.append(AdminPages.banRow(banId, bxuid, reason, createdAt, expiresAt, revokedAt, updatedAt, isActive));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalPlayers", totalPlayers);
        out.put("activeBans", activeBans);
        out.put("revokedBans", revokedBans);
        out.put("totalBans", totalBans);
        out.put("rowsHtml", rows.toString());
        return JsonUtil.OM.writeValueAsString(out);
    }

    public String statsHistoryJson(String serverKey, int limit) throws Exception {
        String sk = (serverKey == null || serverKey.isBlank()) ? firstServerKey() : serverKey;
        int lim = Math.max(10, Math.min(limit, 2000));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("serverKey", sk);
        out.put("latest", (sk == null) ? null : metricsRepo.loadLatest(sk));
        out.put("points", (sk == null) ? List.of() : metricsRepo.loadHistory(sk, lim));
        return JsonUtil.OM.writeValueAsString(out);
    }

    public String auditLiveJson(String actor, String action, String q, Integer limit) throws Exception {
        int lim = (limit == null) ? 500 : Math.max(50, Math.min(limit, 2000));
        List<AuditEntry> entries = loadAuditEntries(actor, action, q, lim);

        StringBuilder rows = new StringBuilder(120_000);
        for (AuditEntry e : entries) {
            rows.append(AdminPages.auditRow(e.createdAtIso, e.actorUsername, e.actionKey, e.details, e.id));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("resultCount", entries.size());
        out.put("rowsHtml", rows.toString());
        return JsonUtil.OM.writeValueAsString(out);
    }

    private void queueServerCommand(String serverKey, String cmdType, Map<String, Object> payload) throws Exception {
        String json = JsonUtil.OM.writeValueAsString(payload == null ? Map.of() : payload);

        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO server_commands(server_key, cmd_type, payload_json, created_at, acknowledged_at) " +
                             "VALUES(?, ?, ?, CURRENT_TIMESTAMP(3), NULL)"
             )) {
            ps.setString(1, serverKey);
            ps.setString(2, cmdType);
            ps.setString(3, json);
            ps.executeUpdate();
        }

        LiveBus.publishInvalidate("players", "audit");
    }

    private String loadServerOptionsHtml() throws Exception {
        StringBuilder sb = new StringBuilder(4000);

        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT server_key FROM servers ORDER BY created_at ASC LIMIT 500"
             );
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String serverKey = rs.getString(1);
                if (serverKey == null || serverKey.isBlank()) continue;
                sb.append("<option value='")
                        .append(AdminUiUtil.escAttr(serverKey))
                        .append("'>")
                        .append(AdminUiUtil.esc(serverKey))
                        .append("</option>");
            }
        }
        return sb.toString();
    }

    private List<String> loadServerKeys() throws Exception {
        List<String> out = new java.util.ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT server_key FROM servers ORDER BY created_at ASC LIMIT 500"
             );
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String serverKey = rs.getString(1);
                if (serverKey != null && !serverKey.isBlank()) {
                    out.add(serverKey.trim());
                }
            }
        }
        return out;
    }

    private void audit(String actorUsername, String actionKey, String details) {
        if (actionKey == null || actionKey.isBlank()) return;

        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO admin_audit_log(actor_username, action_key, details) VALUES(?, ?, ?)"
             )) {
            ps.setString(1, (actorUsername == null || actorUsername.isBlank()) ? null : actorUsername.trim());
            ps.setString(2, actionKey.trim());
            ps.setString(3, (details == null || details.isBlank()) ? null : details.trim());
            ps.executeUpdate();
        } catch (Exception ignored) {
            // fail-open on purpose
        }
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.replace("\r", " ").replace("\n", " ").trim();
    }

    private Player loadPlayer(String xuid) throws Exception {
        String sql =
                "SELECT p.xuid, p.last_name, p.last_seen_at, " +
                        "CASE WHEN p.online = 1 AND p.online_updated_at IS NOT NULL " +
                        "AND p.online_updated_at >= (CURRENT_TIMESTAMP(3) - INTERVAL " + ONLINE_STALE_SECONDS + " SECOND) THEN 1 ELSE 0 END AS online_effective, " +
                        "p.online_updated_at, p.online_server_key, " +
                        "COALESCE(srv.server_key, '') AS online_server_name, " +
                        "COALESCE(ps.playtime_seconds,0) AS playtime_seconds, " +
                        "COALESCE(ps.kills,0) AS kills, " +
                        "COALESCE(ps.deaths,0) AS deaths " +
                        "FROM players p " +
                        "LEFT JOIN player_stats ps ON ps.xuid=p.xuid " +
                        "LEFT JOIN servers srv ON srv.server_key=p.online_server_key " +
                        "WHERE p.xuid=? LIMIT 1";

        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, xuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;

                String name = rs.getString("last_name");
                if (name == null || name.isBlank()) name = "Unknown";

                return new Player(
                        rs.getString("xuid"),
                        name,
                        rs.getInt("online_effective") == 1,
                        AdminUiUtil.toIso(rs.getTimestamp("online_updated_at")),
                        rs.getString("online_server_key"),
                        rs.getString("online_server_name"),
                        AdminUiUtil.toIso(rs.getTimestamp("last_seen_at")),
                        rs.getLong("playtime_seconds"),
                        rs.getLong("kills"),
                        rs.getLong("deaths")
                );
            }
        }
    }

    private BanState loadActiveBan(String xuid) throws Exception {
        String sql =
                "SELECT ban_id, reason, created_at, expires_at, updated_at " +
                        "FROM bans WHERE xuid=? " +
                        "AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP(3)) " +
                        "ORDER BY updated_at DESC LIMIT 1";

        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, xuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new BanState(
                        rs.getLong("ban_id"),
                        rs.getString("reason") == null ? "" : rs.getString("reason"),
                        AdminUiUtil.toIso(rs.getTimestamp("created_at")),
                        AdminUiUtil.toIsoNullable(rs.getTimestamp("expires_at")),
                        AdminUiUtil.toIso(rs.getTimestamp("updated_at"))
                );
            }
        }
    }

    private List<BanEvent> loadBanEvents(long banId) throws Exception {
        String sql =
                "SELECT event_type, actor_type, actor_username, actor_server_key, created_at, details " +
                        "FROM ban_events WHERE ban_id=? ORDER BY created_at DESC LIMIT 50";

        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, banId);
            try (ResultSet rs = ps.executeQuery()) {
                var out = new java.util.ArrayList<BanEvent>();
                while (rs.next()) {
                    out.add(new BanEvent(
                            rs.getString("event_type"),
                            rs.getString("actor_type"),
                            rs.getString("actor_username"),
                            rs.getString("actor_server_key"),
                            AdminUiUtil.toIso(rs.getTimestamp("created_at")),
                            rs.getString("details")
                    ));
                }
                return out;
            }
        }
    }

    private String firstServerKey() throws Exception {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT server_key FROM servers ORDER BY created_at ASC LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private long count(String sql) throws Exception {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private static boolean hasActiveBan(Connection c, String xuid) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM bans WHERE xuid=? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP(3)) LIMIT 1"
        )) {
            ps.setString(1, xuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean playerExists(Connection c, String xuid) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM players WHERE xuid=? LIMIT 1")) {
            ps.setString(1, xuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void upsertPlayerStub(Connection c, String xuid) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO players(xuid, last_name, last_seen_at) VALUES(?, 'Unknown', CURRENT_TIMESTAMP(3)) " +
                        "ON DUPLICATE KEY UPDATE last_seen_at=CURRENT_TIMESTAMP(3)"
        )) {
            ps.setString(1, xuid);
            ps.executeUpdate();
        }
    }

    private List<WebRole> loadRoles() throws Exception {
        var out = new java.util.ArrayList<WebRole>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id, role_key, display_name FROM web_roles ORDER BY role_key ASC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(new WebRole(rs.getLong(1), rs.getString(2), rs.getString(3)));
        }
        return out;
    }

    private List<WebPerm> loadPermissions() throws Exception {
        var out = new java.util.ArrayList<WebPerm>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT perm_key, description FROM web_permissions ORDER BY perm_key ASC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(new WebPerm(rs.getString(1), rs.getString(2)));
        }
        return out;
    }

    private Map<Long, Map<String, Boolean>> loadRolePermissionsByRoleId() throws Exception {
        String sql =
                "SELECT rp.role_id, p.perm_key " +
                        "FROM web_role_permissions rp " +
                        "JOIN web_permissions p ON p.id = rp.perm_id";

        Map<Long, Map<String, Boolean>> out = new LinkedHashMap<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long roleId = rs.getLong(1);
                String pk = rs.getString(2);
                out.computeIfAbsent(roleId, __ -> new LinkedHashMap<>()).put(pk, true);
            }
        }
        return out;
    }

    private static long permIdByKey(Connection c, String permKey) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM web_permissions WHERE perm_key=? LIMIT 1")) {
            ps.setString(1, permKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        }
    }

    public record WebRole(long id, String roleKey, String displayName) {}
    public record WebPerm(String permKey, String description) {}

    private record Player(
            String xuid,
            String name,
            boolean online,
            String onlineUpdatedIso,
            String onlineServerKey,
            String onlineServerName,
            String lastSeenIso,
            long playtimeSeconds,
            long kills,
            long deaths
    ) {}

    private record BanState(long banId, String reason, String createdAtIso, String expiresAtIso, String updatedAtIso) {}

    public record BanEvent(
            String eventType,
            String actorType,
            String actorUsername,
            String actorServerKey,
            String createdAtIso,
            String details
    ) {}
}