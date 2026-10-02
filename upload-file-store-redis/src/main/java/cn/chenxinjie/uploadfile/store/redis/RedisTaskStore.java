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
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
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
 * is gone, so the index can no longer grow without bound. {@link #list()} reads task payloads with
 * batched {@code MGET} calls instead of one {@code GET} per identifier.</p>
 *
 * <p>Records written by rc.6 used a plain {@code SET} index; the first read/write lazily migrates it
 * to a sorted set, so an upgrade needs no manual step. Since rc.8 that migration runs as one Lua
 * script (an atomic {@code RENAME} to a staging key, then {@code ZADD} + {@code DEL}), so a
 * concurrent {@code save()} during migration can never drop an identifier.</p>
 *
 * <p>{@link #list()} reads task payloads in bounded {@code MGET} batches (instead of one unbounded
 * {@code MGET}) so a very large index cannot monopolise the single-threaded Redis for one call; the
 * identifier snapshot is taken first, keeping one pass internally consistent.</p>
 *
 * <p>Clock sensitivity (L6): index scores are stamped with the <em>client's</em> wall clock and the
 * TTL pruning compares them with the local clock, so instances with skewed clocks can prune
 * too aggressively (entries younger than the TTL dropped) or leave expired entries listed until a
 * save refreshes them. Keep the clocks of all instances sharing a store synchronized (e.g. NTP).</p>
 *
 * <p>The core does not depend on third-party frameworks; the Jedis client dependency stays in this module.</p>
 */
public class RedisTaskStore implements TaskStore {

    public static final String DEFAULT_KEY_PREFIX = "upload:task:";
    public static final String DEFAULT_INDEX_KEY = "upload:task:index";

    /** Maximum identifiers fetched per {@code MGET} round trip in {@link #list()} (rc.8). */
    static final int MGET_BATCH_SIZE = 500;

    /**
     * Atomically migrates the legacy {@code SET} index (rc.6) to a sorted set (rc.8). {@code KEYS[1]}
     * is the live index, {@code KEYS[2]} the staging key and {@code ARGV[1]} the score to stamp on
     * migrated entries. Within Redis's single thread this is a {@code TYPE} probe, an atomic
     * {@code RENAME} away from the live key, then {@code SMEMBERS → ZADD → DEL} on the staging key,
     * so a concurrent {@code save()} (which writes to the live index) is never lost.
     */
    private static final String MIGRATE_SCRIPT =
            "local t = redis.call('TYPE', KEYS[1])['ok'] "
                    + "if t == 'set' then "
                    + "  if redis.call('EXISTS', KEYS[2]) == 1 then redis.call('DEL', KEYS[2]) end "
                    + "  redis.call('RENAME', KEYS[1], KEYS[2]) "
                    + "end "
                    + "if redis.call('EXISTS', KEYS[2]) == 1 then "
                    + "  local members = redis.call('SMEMBERS', KEYS[2]) "
                    + "  local now = tonumber(ARGV[1]) "
                    + "  for i = 1, #members do "
                    + "    redis.call('ZADD', KEYS[1], now, members[i]) "
                    + "  end "
                    + "  redis.call('DEL', KEYS[2]) "
                    + "end "
                    + "return 1";

    private final JedisPool pool;
    private final String keyPrefix;
    private final String indexKey;
    private final String indexStagingKey;
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
        this.indexStagingKey = this.indexKey + ":migrating";
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
        jedis.eval(MIGRATE_SCRIPT, Arrays.asList(indexKey, indexStagingKey),
                Collections.singletonList(String.valueOf(System.currentTimeMillis())));
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
            // Snapshot the identifiers first, so one list() pass is internally consistent, then read
            // the payloads in bounded MGET batches (rc.8, G20) instead of one unbounded MGET that
            // would block Redis's single thread on a very large index.
            List<String> identifiers = jedis.zrange(indexKey, 0, -1);
            if (identifiers.isEmpty()) {
                return result;
            }
            for (int start = 0; start < identifiers.size(); start += MGET_BATCH_SIZE) {
                int end = Math.min(start + MGET_BATCH_SIZE, identifiers.size());
                List<String> batch = identifiers.subList(start, end);
                byte[][] keys = new byte[batch.size()][];
                for (int i = 0; i < batch.size(); i++) {
                    keys[i] = key(batch.get(i)).getBytes(StandardCharsets.UTF_8);
                }
                List<byte[]> values = jedis.mget(keys);
                for (int i = 0; i < batch.size(); i++) {
                    byte[] raw = values.get(i);
                    if (raw == null) {
                        // The task key expired but its index entry lingered: prune it lazily.
                        jedis.zrem(indexKey, batch.get(i));
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
        }
        return result;
    }
}
