package com.infernalsuite.asp.loaders.redis;

import com.infernalsuite.asp.api.exceptions.UnknownWorldException;
import com.infernalsuite.asp.api.loaders.SlimeLoader;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;
import com.infernalsuite.asp.loaders.redis.util.StringByteCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Redis-compatible loader for SlimeWorld.
 * Supports Redis, DragonFly, and Valkey (all Redis-protocol compatible).
 *
 * DragonFly: https://dragonflydb.io/ — drop-in replacement for Redis, multi-threaded
 * Valkey: https://valkey.io/ — open-source fork of Redis (Linux Foundation)
 *
 * All three use the same Redis protocol, so the same client code works.
 * Just provide the appropriate URI:
 *   redis://host:port
 *   rediss://host:port (TLS)
 *   redis+sentinel://host:port (Redis Sentinel)
 *   dragonfly://host:port (DragonFly — same as redis://)
 *   valkey://host:port (Valkey — same as redis://)
 */
public class RedisLoader implements SlimeLoader {

    private static final String WORLD_DATA_PREFIX = "aswm:world:data:";
    private static final String WORLD_LIST_KEY = "aswm:world:list";

    private final RedisCommands<String, byte[]> connection;

    /**
     * Creates a Redis-compatible loader.
     *
     * @param uri Connection URI. Supports:
     *            - redis://host:port (standard Redis)
     *            - rediss://host:port (Redis with TLS)
     *            - redis+sentinel://host:port (Redis Sentinel)
     *            - Any DragonFly or Valkey URI (they use the same protocol)
     */
    public RedisLoader(String uri) {
        // DragonFly and Valkey are fully Redis-protocol compatible.
        // Lettuce client works with all of them without any changes.
        // Just normalize the URI scheme if needed.
        String normalizedUri = normalizeUri(uri);

        this.connection = RedisClient
                .create(normalizedUri)
                .connect(StringByteCodec.INSTANCE)
                .sync();
    }

    /**
     * Normalizes the URI for DragonFly and Valkey compatibility.
     * Both use the Redis protocol, so we just ensure the scheme is recognized by Lettuce.
     */
    private static String normalizeUri(String uri) {
        if (uri == null || uri.isEmpty()) {
            throw new IllegalArgumentException("Redis URI cannot be null or empty");
        }

        // DragonFly uses redis:// by default, but some configs may use dragonfly://
        if (uri.startsWith("dragonfly://")) {
            return uri.replace("dragonfly://", "redis://");
        }

        // Valkey uses redis:// by default, but some configs may use valkey://
        if (uri.startsWith("valkey://")) {
            return uri.replace("valkey://", "redis://");
        }

        // DragonFly with TLS
        if (uri.startsWith("dragonfly+ssl://") || uri.startsWith("dragonflyssl://")) {
            return uri.replace("dragonfly+ssl://", "rediss://").replace("dragonflyssl://", "rediss://");
        }

        // Valkey with TLS
        if (uri.startsWith("valkey+ssl://") || uri.startsWith("valkeyssl://")) {
            return uri.replace("valkey+ssl://", "rediss://").replace("valkeyssl://", "rediss://");
        }

        return uri;
    }

    @Override
    public byte[] readWorld(String name) throws UnknownWorldException, IOException {
        byte[] data = connection.get(WORLD_DATA_PREFIX + name);
        if (data == null) {
            throw new UnknownWorldException(name);
        }
        return data;
    }

    @Override
    public boolean worldExists(String name) throws IOException {
        return connection.exists(WORLD_DATA_PREFIX + name) == 1;
    }

    @Override
    public List<String> listWorlds() throws IOException {
        return connection.smembers(WORLD_LIST_KEY)
                .stream()
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .collect(Collectors.toList());
    }

    @Override
    public void saveWorld(String worldName, byte[] bytes) throws IOException {
        connection.set(WORLD_DATA_PREFIX + worldName, bytes);
        connection.sadd(WORLD_LIST_KEY, worldName.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void deleteWorld(String worldName) throws UnknownWorldException, IOException {
        long deletedCount = connection.del(WORLD_DATA_PREFIX + worldName);
        if (deletedCount == 0) {
            throw new UnknownWorldException(worldName);
        }
        connection.srem(WORLD_LIST_KEY, worldName.getBytes(StandardCharsets.UTF_8));
    }
}
