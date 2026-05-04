package dev.btc.core.security;

import org.bukkit.Bukkit;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.List;
import java.util.ArrayList;
import java.util.logging.Level;

/**
 * Handles asynchronous MySQL logging for Native Sentinel's violation reports.
 */
public class NativeAnticheatDB {

    private static String url = "";
    private static String user = "";
    private static String password = "";
    private static boolean enabled = false;

    private static final ExecutorService dbThreadPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Native-Sentinel-DB");
        t.setDaemon(true);
        return t;
    });

    public static void init(String host, int port, String database, String username, String pass) {
        if (host == null || host.isEmpty()) return;
        
        url = "jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false&autoReconnect=true";
        user = username;
        password = pass;
        enabled = true;

        dbThreadPool.submit(() -> {
            try (Connection conn = getConnection()) {
                String createTable = "CREATE TABLE IF NOT EXISTS sentinel_violations (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "player_uuid VARCHAR(36) NOT NULL, " +
                        "player_name VARCHAR(16) NOT NULL, " +
                        "check_type VARCHAR(50) NOT NULL, " +
                        "details VARCHAR(255) NOT NULL, " +
                        "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                        ");";
                try (PreparedStatement stmt = conn.prepareStatement(createTable)) {
                    stmt.execute();
                }
                Bukkit.getLogger().info("[SentinelDB] MySQL liaison established & schema verified.");
            } catch (SQLException e) {
                Bukkit.getLogger().log(Level.WARNING, "[SentinelDB] Failed to connect to MySQL database.", e);
                enabled = false;
            }
        });
    }

    public static void reportViolation(String uuid, String name, String checkType, String details) {
        if (!enabled) return;
        
        dbThreadPool.submit(() -> {
            String insert = "INSERT INTO sentinel_violations (player_uuid, player_name, check_type, details) VALUES (?, ?, ?, ?)";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(insert)) {
                
                stmt.setString(1, uuid);
                stmt.setString(2, name);
                stmt.setString(3, checkType);
                stmt.setString(4, details);
                
                stmt.executeUpdate();
            } catch (SQLException e) {
                Bukkit.getLogger().log(Level.WARNING, "[SentinelDB] Could not log violation for " + name, e);
            }
        });
    }

    public static void fetchRecentViolationsAsync(String playerName, Consumer<List<String>> callback) {
        if (!enabled) {
            callback.accept(List.of("[SentinelDB] MySQL logging is DISABLED natively !"));
            return;
        }

        dbThreadPool.submit(() -> {
            List<String> logs = new ArrayList<>();
            String query = "SELECT check_type, details, timestamp FROM sentinel_violations WHERE player_name = ? ORDER BY timestamp DESC LIMIT 10";
            
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                 
                stmt.setString(1, playerName);
                var rs = stmt.executeQuery();
                
                while (rs.next()) {
                    String time = rs.getTimestamp("timestamp").toString();
                    String type = rs.getString("check_type");
                    String details = rs.getString("details");
                    logs.add(time + " - " + type + " - " + details);
                }
            } catch (SQLException e) {
                Bukkit.getLogger().log(Level.WARNING, "[SentinelDB] Could not search violations for " + playerName, e);
                logs.add("Database search failed. See console.");
            }
            
            callback.accept(logs);
        });
    }

    private static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }
}

