package com.infernalsuite.asp.loaders.mysql;

import com.infernalsuite.asp.api.exceptions.UnknownWorldException;
import com.infernalsuite.asp.api.loaders.UpdatableLoader;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL loader for SlimeWorld.
 * Uses the same schema as MySQL but with PostgreSQL-compatible SQL syntax.
 */
public class PostgresLoader extends UpdatableLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(PostgresLoader.class);
    private static final int CURRENT_DB_VERSION = 1;

    // PostgreSQL uses SERIAL instead of AUTO_INCREMENT, and different syntax
    private static final String CREATE_VERSIONING_TABLE_QUERY =
            "CREATE TABLE IF NOT EXISTS database_version (id SERIAL PRIMARY KEY, version INTEGER);";
    private static final String INSERT_VERSION_QUERY =
            "INSERT INTO database_version (id, version) VALUES (1, ?) ON CONFLICT (id) DO UPDATE SET version = ?;";
    private static final String GET_VERSION_QUERY = "SELECT version FROM database_version WHERE id = 1;";
    private static final String ALTER_LOCKED_COLUMN_QUERY =
            "ALTER TABLE worlds ALTER COLUMN locked TYPE BIGINT;";

    private static final String CREATE_WORLDS_TABLE_QUERY =
            "CREATE TABLE IF NOT EXISTS worlds (id SERIAL PRIMARY KEY, name VARCHAR(255) UNIQUE, locked BIGINT DEFAULT 0, world BYTEA);";
    private static final String SELECT_WORLD_QUERY = "SELECT world FROM worlds WHERE name = ?;";
    private static final String UPDATE_WORLD_QUERY =
            "INSERT INTO worlds (name, world) VALUES (?, ?) ON CONFLICT (name) DO UPDATE SET world = ?;";
    private static final String DELETE_WORLD_QUERY = "DELETE FROM worlds WHERE name = ?;";
    private static final String LIST_WORLDS_QUERY = "SELECT name FROM worlds;";

    private final HikariDataSource source;

    public PostgresLoader(String host, int port, String database, boolean useSSL, String username, String password) throws SQLException {
        HikariConfig hikariConfig = new HikariConfig();
        String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s?ssl=%b", host, port, database, useSSL);
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        hikariConfig.setDriverClassName("org.postgresql.Driver");
        hikariConfig.setMaximumPoolSize(10);
        hikariConfig.setConnectionTimeout(5000);
        hikariConfig.setPoolName("BTCCore-Postgres-Pool");

        this.source = new HikariDataSource(hikariConfig);
        init();
    }

    public PostgresLoader(HikariDataSource hikariDataSource) throws SQLException {
        this.source = hikariDataSource;
        init();
    }

    public void close() {
        if (source != null && !source.isClosed()) {
            source.close();
        }
    }

    @Override
    public void update() throws IOException, NewerStorageException {
        try (Connection con = source.getConnection()) {
            int version;
            try (PreparedStatement statement = con.prepareStatement(GET_VERSION_QUERY);
                 ResultSet set = statement.executeQuery()) {
                version = set.next() ? set.getInt(1) : -1;
            }

            if (version > CURRENT_DB_VERSION) {
                throw new NewerStorageException(CURRENT_DB_VERSION, version);
            }

            if (version < CURRENT_DB_VERSION) {
                LOGGER.warn("Your SWM PostgreSQL database is outdated. The update process will start in 10 seconds.");
                LOGGER.warn("Shut down the server to prevent your database from being updated.");

                try {
                    Thread.sleep(10000L);
                } catch (InterruptedException ignored) {
                    LOGGER.info("Update process aborted.");
                    return;
                }

                try (PreparedStatement statement = con.prepareStatement(ALTER_LOCKED_COLUMN_QUERY)) {
                    statement.executeUpdate();
                }

                try (PreparedStatement statement = con.prepareStatement(INSERT_VERSION_QUERY)) {
                    statement.setInt(1, CURRENT_DB_VERSION);
                    statement.setInt(2, CURRENT_DB_VERSION);
                    statement.executeUpdate();
                }
            }
        } catch (SQLException ex) {
            throw new IOException(ex);
        }
    }

    @Override
    public byte[] readWorld(String worldName) throws UnknownWorldException, IOException {
        try (Connection con = source.getConnection();
             PreparedStatement statement = con.prepareStatement(SELECT_WORLD_QUERY)) {
            statement.setString(1, worldName);
            ResultSet set = statement.executeQuery();
            if (!set.next()) {
                throw new UnknownWorldException(worldName);
            }
            return set.getBytes("world");
        } catch (SQLException ex) {
            throw new IOException(ex);
        }
    }

    @Override
    public boolean worldExists(String worldName) throws IOException {
        try (Connection con = source.getConnection();
             PreparedStatement statement = con.prepareStatement(SELECT_WORLD_QUERY)) {
            statement.setString(1, worldName);
            ResultSet set = statement.executeQuery();
            return set.next();
        } catch (SQLException ex) {
            throw new IOException(ex);
        }
    }

    @Override
    public List<String> listWorlds() throws IOException {
        List<String> worldList = new ArrayList<>();
        try (Connection con = source.getConnection();
             PreparedStatement statement = con.prepareStatement(LIST_WORLDS_QUERY)) {
            ResultSet set = statement.executeQuery();
            while (set.next()) {
                worldList.add(set.getString("name"));
            }
        } catch (SQLException ex) {
            throw new IOException(ex);
        }
        return worldList;
    }

    @Override
    public void saveWorld(String worldName, byte[] serializedWorld) throws IOException {
        try (Connection con = source.getConnection();
             PreparedStatement statement = con.prepareStatement(UPDATE_WORLD_QUERY)) {
            statement.setString(1, worldName);
            statement.setBytes(2, serializedWorld);
            statement.setBytes(3, serializedWorld);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IOException(ex);
        }
    }

    @Override
    public void deleteWorld(String worldName) throws IOException, UnknownWorldException {
        try (Connection con = source.getConnection();
             PreparedStatement statement = con.prepareStatement(DELETE_WORLD_QUERY)) {
            statement.setString(1, worldName);
            if (statement.executeUpdate() == 0) {
                throw new UnknownWorldException(worldName);
            }
        } catch (SQLException ex) {
            throw new IOException(ex);
        }
    }

    private void init() throws SQLException {
        try (Connection con = source.getConnection()) {
            try (PreparedStatement statement = con.prepareStatement(CREATE_WORLDS_TABLE_QUERY)) {
                statement.execute();
            }
            try (PreparedStatement statement = con.prepareStatement(CREATE_VERSIONING_TABLE_QUERY)) {
                statement.execute();
            }
        }
    }
}
