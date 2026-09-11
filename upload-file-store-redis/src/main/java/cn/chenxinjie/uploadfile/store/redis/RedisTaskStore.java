/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.store.redis;

import cn.chenxinjie.uploadfile.core.model.UploadTask;
import cn.chenxinjie.uploadfile.core.store.TaskStore;
import cn.chenxinjie.uploadfile.core.util.StringUtil;
import com.google.gson.Gson;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis-backed {@link TaskStore} based on Jedis. Each task is stored as a string key
 * {@code <keyPrefix><identifier>} holding the JSON metadata; the identifiers are tracked in a
 * {@code <keyPrefix>index} <b>sorted set</b> so {@link #list()} can enumerate them.
 *
 * <h2>Index governance (rc.7)</h2>
 *
 * <p>The index is a sorted set scored by the time the identifier was last written. When a per-task
 * TTL is configured, {@link #list()} first drops index entries whose score is older than the TTL
 * (those keys have certainly expired) and then lazily {@code ZREM}s any identifier whose task key
 * is gone, so the index can no longer grow without bound. {@link #list()} reads all task payloads
 * with a single {@code MGET} instead of one {@code GET} per identifier.</p>
 *
 * <p>Records written by rc.6 used a plain {@code SET} index; the first read/write lazily migrates it
 * to a sorted set, so an upgrade needs no manual step.</p>
 *
 * <p>The core does not depend on third-party frameworks; the Jedis client dependency stays in this module.</p>
 */
public class RedisTaskStore implements TaskStore {

    public static final String DEFAULT_KEY_PREFIX = "upload:task:";
    public static final String DEFAULT_INDEX_KEY = "upload:task:index";

    private final JedisPool pool;
    private final String keyPrefix;
    private final String indexKey;
    private final int ttlSeconds;
    private final Gson gson = new Gson();

    /** The legacy {@code SET} index is migrated to a sorted set at most once per JVM. */
    private final AtomicBoolean indexMigrated = new AtomicBoolean(false);

    public RedisTaskStore(JedisPool pool) {
        this(pool, DEFAULT_KEY_PREFIX, 0);
    }

    public RedisTaskStore(JedisPool pool, String keyPrefix, int ttlSeconds) {
        this.pool = pool;
        this.keyPrefix = StringUtil.isBlank(keyPrefix) ? DEFAULT_KEY_PREFIX : keyPrefix;
        this.indexKey = this.keyPrefix + "index";
        this.ttlSeconds = Math.max(0, ttlSeconds);
    }

    /**
     * Convenience factory creating a {@link JedisPool} from host/port/password.
     */
    public static RedisTaskStore create(String host, int port, String password, String keyPrefix, int ttlSeconds) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(16);
        JedisPool pool = password == null || password.isEmpty()
                ? new JedisPool(config, host, port, Protocol.DEFAULT_TIMEOUT)
                : new JedisPool(config, host, port, Protocol.DEFAULT_TIMEOUT, password);
        return new RedisTaskStore(pool, keyPrefix, ttlSeconds);
    }

    private String key(String identifier) {
        return keyPrefix + identifier;
    }

    /**
     * Migrates the legacy {@code SET} index (rc.6) to a sorted set, preserving every identifier.
     * Idempotent and safe to call on every operation; the {@code TYPE} probe only runs until the
     * migration has been observed once.
     */
    private void ensureIndexMigrated(Jedis jedis) {
        if (indexMigrated.get()) {
            return;
        }
        String type = jedis.type(indexKey);
        if ("set".equals(type)) {
            Set<String> identifiers = jedis.smembers(indexKey);
            jedis.del(indexKey);
            if (!identifiers.isEmpty()) {
                long now = System.currentTimeMillis();
                Map<String, Double> scored = new HashMap<>();
                for (String identifier : identifiers) {
                    scored.put(identifier, (double) now);
                }
                jedis.zadd(indexKey, scored);
            }
        }
        indexMigrated.set(true);
    }

    @Override
    public Optional<UploadTask> get(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return Optional.empty();
        }
        StringUtil.requireSafeIdentifier(identifier);
        try (Jedis jedis = pool.getResource()) {
            String json = jedis.get(key(identifier));
            if (json == null) {
                return Optional.empty();
            }
            UploadTask task = gson.fromJson(json, UploadTask.class);
            if (task == null) {
                return Optional.empty();
            }
            task.normalize();
            return Optional.of(task);
        }
    }

    @Override
    public void save(UploadTask task) {
        StringUtil.requireSafeIdentifier(task.getIdentifier());
        String json = gson.toJson(task);
        try (Jedis jedis = pool.getResource()) {
            ensureIndexMigrated(jedis);
            if (ttlSeconds > 0) {
                jedis.setex(key(task.getIdentifier()), ttlSeconds, json);
            } else {
                jedis.set(key(task.getIdentifier()), json);
            }
            // Score by write time: the key expires at write time + ttl, so an index entry whose
            // score is older than the TTL is guaranteed stale (see list()).
            jedis.zadd(indexKey, System.currentTimeMillis(), task.getIdentifier());
        }
    }

    @Override
    public boolean remove(String identifier) {
        StringUtil.requireSafeIdentifier(identifier);
        try (Jedis jedis = pool.getResource()) {
            ensureIndexMigrated(jedis);
            long removed = jedis.del(key(identifier));
            jedis.zrem(indexKey, identifier);
            return removed > 0;
        }
    }

    @Override
    public Collection<UploadTask> list() {
        List<UploadTask> result = new ArrayList<>();
        try (Jedis jedis = pool.getResource()) {
            ensureIndexMigrated(jedis);
            if (ttlSeconds > 0) {
                // Drop index entries whose task key has certainly expired.
                jedis.zremrangeByScore(indexKey, 0, System.currentTimeMillis() - ttlSeconds * 1000L);
            }
            List<String> identifiers = jedis.zrange(indexKey, 0, -1);
            if (identifiers.isEmpty()) {
                return result;
            }
            // One MGET instead of N GETs.
            byte[][] keys = new byte[identifiers.size()][];
            for (int i = 0; i < identifiers.size(); i++) {
                keys[i] = key(identifiers.get(i)).getBytes(StandardCharsets.UTF_8);
            }
            List<byte[]> values = jedis.mget(keys);
            for (int i = 0; i < identifiers.size(); i++) {
                byte[] raw = values.get(i);
                if (raw == null) {
                    // The task key expired but its index entry lingered: prune it lazily.
                    jedis.zrem(indexKey, identifiers.get(i));
                    continue;
                }
                try {
                    UploadTask task = gson.fromJson(new String(raw, StandardCharsets.UTF_8), UploadTask.class);
                    if (task != null) {
                        task.normalize();
                        result.add(task);
                    }
                } catch (RuntimeException ignored) {
                    // Skip a corrupt record without failing the whole list operation.
                }
            }
        }
        return result;
    }
}
