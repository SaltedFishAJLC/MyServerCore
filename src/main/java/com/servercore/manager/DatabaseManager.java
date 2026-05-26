package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class DatabaseManager {

    private final ServerCorePlugin plugin;
    private HikariDataSource dataSource;

    public DatabaseManager(ServerCorePlugin plugin) {
        this.plugin = plugin;
        initDatabase();
    }

    private void initDatabase() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        File dbFile = new File(dataFolder, "economy.db");
        String jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setPoolName("ServerCore-EconomyPool");
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(10000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        dataSource = new HikariDataSource(config);

        createTables();
    }

    private void createTables() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            String sql = "CREATE TABLE IF NOT EXISTS players_economy (" +
                         "uuid VARCHAR(36) PRIMARY KEY, " +
                         "balance BIGINT NOT NULL DEFAULT 0" +
                         ");";
            stmt.execute(sql);

            String sqlSoul = "CREATE TABLE IF NOT EXISTS soul_containers (" +
                             "container_id VARCHAR(36) PRIMARY KEY, " +
                             "owner_uuid VARCHAR(36) NOT NULL, " +
                             "items_data BLOB NOT NULL, " +
                             "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                             ");";
            stmt.execute(sqlSoul);

            String sqlStash = "CREATE TABLE IF NOT EXISTS player_stash (" +
                              "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                              "owner_uuid VARCHAR(36) NOT NULL, " +
                              "item_data BLOB NOT NULL, " +
                              "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                              ");";
            stmt.execute(sqlStash);
        } catch (SQLException e) {
            plugin.getComponentLogger().error(ServerCorePlugin.getMiniMessage().deserialize("<red>Failed to create database tables!</red>"));
            e.printStackTrace();
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * 异步加载玩家金币数据。如果数据库没有记录，则返回默认值 0。
     */
    public CompletableFuture<Long> loadPlayerBalance(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT balance FROM players_economy WHERE uuid = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, uuid.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getLong("balance");
                    }
                }
            } catch (SQLException e) {
                plugin.getComponentLogger().error(ServerCorePlugin.getMiniMessage().deserialize("<red>Error loading balance for " + uuid + "</red>"));
                e.printStackTrace();
            }
            return 0L;
        });
    }

    /**
     * 同步保存玩家金币数据，供退出服务器时或定期保存调用。
     * 可以放在异步线程池里调用。
     */
    public void savePlayerBalanceSync(UUID uuid, long balance) {
        String sql = "INSERT INTO players_economy (uuid, balance) VALUES (?, ?) " +
                     "ON CONFLICT(uuid) DO UPDATE SET balance = excluded.balance";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, uuid.toString());
            pstmt.setLong(2, balance);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getComponentLogger().error(ServerCorePlugin.getMiniMessage().deserialize("<red>Error saving balance for " + uuid + "</red>"));
            e.printStackTrace();
        }
    }

    /**
     * 异步保存玩家金币数据。
     */
    public void savePlayerBalanceAsync(UUID uuid, long balance) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> savePlayerBalanceSync(uuid, balance));
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
