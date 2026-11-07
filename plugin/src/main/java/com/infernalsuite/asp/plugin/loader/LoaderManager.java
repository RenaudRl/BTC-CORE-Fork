package com.infernalsuite.asp.plugin.loader;

import com.infernalsuite.asp.api.loaders.SlimeLoader;
import com.infernalsuite.asp.api.loaders.UpdatableLoader;
import com.infernalsuite.asp.loaders.api.APILoader;
import com.infernalsuite.asp.loaders.file.FileLoader;
import com.infernalsuite.asp.loaders.mongo.MongoLoader;
import com.infernalsuite.asp.loaders.mysql.MysqlLoader;
import com.infernalsuite.asp.loaders.mysql.PostgresLoader;
import com.infernalsuite.asp.loaders.redis.RedisLoader;
import com.infernalsuite.asp.plugin.config.ConfigManager;
import com.infernalsuite.asp.plugin.config.DatasourcesConfig;
import com.infernalsuite.asp.plugin.config.SourceConfigurationException;
import com.infernalsuite.asp.plugin.config.SourceValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * BTC-Core: Data source loader manager.
 * Supports: File, MySQL, PostgreSQL, MongoDB, Redis, DragonFly, Valkey, API
 *
 * <p>String values of {@code sources.yml} accept {@code ${env:NAME}} (AD-096, see
 * {@link SourceValues}). A source whose value cannot be resolved is not opened, and the error
 * names the key and the variable — never the value.
 */
public class LoaderManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoaderManager.class);

    private final Map<String, SlimeLoader> loaders = new HashMap<>();

    @FunctionalInterface
    private interface Opener {
        SlimeLoader open() throws Exception;
    }

    public LoaderManager() {
        DatasourcesConfig config = ConfigManager.getDatasourcesConfig();
        SourceValues values = SourceValues.fromProcessEnvironment();

        // File loader (always enabled)
        DatasourcesConfig.FileConfig fileConfig = config.getFileConfig();
        registerLoader("file", new FileLoader(new File(fileConfig.getPath())));

        DatasourcesConfig.MysqlConfig mysql = config.getMysqlConfig();
        if (mysql.isEnabled()) {
            open("mysql", () -> new MysqlLoader(
                    mysql.getSqlUrl(),
                    values.text("mysql.host", mysql.getHost()), mysql.getPort(),
                    values.text("mysql.database", mysql.getDatabase()), mysql.isUsessl(),
                    values.text("mysql.username", mysql.getUsername()),
                    values.text("mysql.password", mysql.getPassword())
            ));
        }

        DatasourcesConfig.PostgresConfig postgres = config.getPostgresConfig();
        if (postgres.isEnabled()) {
            open("postgresql", () -> {
                Duration lease = values.seconds("postgresql.lock-lease-seconds", postgres.getLockLeaseSeconds());
                return new PostgresLoader(
                        values.urlSafe("postgresql.host", postgres.getHost()),
                        values.port("postgresql.port", postgres.getPort()),
                        values.urlSafe("postgresql.database", postgres.getDatabase()), postgres.isUsessl(),
                        values.text("postgresql.username", postgres.getUsername()),
                        values.text("postgresql.password", postgres.getPassword()),
                        lease,
                        values.renewal("postgresql.lock-renew-seconds", postgres.getLockRenewSeconds(),
                                "postgresql.lock-lease-seconds", lease)
                );
            });
        }

        DatasourcesConfig.MongoDBConfig mongo = config.getMongoDbConfig();
        if (mongo.isEnabled()) {
            open("mongodb", () -> new MongoLoader(
                    values.text("mongodb.database", mongo.getDatabase()),
                    mongo.getCollection(),
                    values.text("mongodb.username", mongo.getUsername()),
                    values.text("mongodb.password", mongo.getPassword()),
                    mongo.getAuthSource(),
                    values.text("mongodb.host", mongo.getHost()),
                    mongo.getPort(),
                    values.text("mongodb.uri", mongo.getUri())
            ));
        }

        // Redis / DragonFly / Valkey: all three are Redis-protocol compatible and use the same
        // loader, the URI scheme being normalized internally. The URI is never logged: resolved
        // from the environment, it may carry a password.
        DatasourcesConfig.RedisConfig redis = config.getRedisConfig();
        if (redis.isEnabled()) {
            String backendType = redis.getBackendType() == null ? "redis" : redis.getBackendType().toLowerCase();
            open(backendType, () -> new RedisLoader(values.text("redis.uri", redis.getUri())));
        }

        DatasourcesConfig.APIConfig api = config.getApiConfig();
        if (api.isEnabled()) {
            open("api", () -> new APILoader(
                    values.text("api.url", api.getUrl()),
                    values.text("api.username", api.getUsername()),
                    values.text("api.token", api.getToken()),
                    api.isIgnoreSslCertificate()
            ));
        }
    }

    private void open(String dataSource, Opener opener) {
        final SlimeLoader loader;
        try {
            loader = opener.open();
        } catch (final SourceConfigurationException refused) {
            // Names the key and the variable, never the value: logged as is, without a stack trace.
            LOGGER.error("Data source {} is not opened: {}", dataSource, refused.getMessage());
            return;
        } catch (final Exception ex) {
            LOGGER.error("Failed to establish connection to the {} data source:", dataSource, ex);
            return;
        }
        registerLoader(dataSource, loader);
    }

    public void registerLoader(String dataSource, SlimeLoader loader) {
        if (loaders.containsKey(dataSource)) {
            throw new IllegalArgumentException("Data source " + dataSource + " already has a declared loader!");
        }

        if (loader instanceof UpdatableLoader) {
            try {
                ((UpdatableLoader) loader).update();
            } catch (final UpdatableLoader.NewerStorageException e) {
                LOGGER.error("Data source {} version is {}, while this loader version only supports up to version {}.",
                        dataSource, e.getStorageVersion(), e.getImplementationVersion(), e);
                return;
            } catch (final IOException ex) {
                LOGGER.error("Failed to update data source {}", dataSource, ex);
                return;
            }
        }

        loaders.put(dataSource, loader);
    }

    /**
     * The loader registered under {@code dataSource} ({@code file}, {@code postgresql}, …), or
     * {@code null} when that source is not configured or failed to open. Extensions that keep
     * their own worlds (BTCSky islands) look their source up here by name.
     */
    public SlimeLoader getLoader(String dataSource) {
        return loaders.get(dataSource);
    }

    public Map<String, SlimeLoader> getLoaders() {
        return loaders;
    }
}
