/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.store.redis;

import cn.chenxinjie.uploadfile.core.model.UploadTask;
import cn.chenxinjie.uploadfile.core.store.QuotaStore;
import cn.chenxinjie.uploadfile.core.store.TaskStore;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Protocol;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Atomic {@link QuotaStore} backed by Redis (rc.7). A per-identifier usage hash plus a total counter
 * are updated by Lua scripts, so the "check + reserve" step is atomic and concurrent uploads across
 * instances cannot overshoot {@code quota.max-bytes}.
 *
 * <p>The counter can be rebuilt from the authoritative {@link TaskStore} with {@link #reconcile(TaskStore)}
 * (call it at startup or from the cleanup scheduler) so it cannot drift.</p>
 */
public class RedisQuotaStore implements QuotaStore {

    public static final String DEFAULT_KEY_PREFIX = "upload:quota:";

    /** Atomically sets this identifier's attributed size and checks the total against the limit. */
    private static final String RESERVE_SCRIPT =
            "local cur = tonumber(redis.call('HGET', KEYS[1], ARGV[1]) or '0') "
                    + "local total = tonumber(redis.call('GET', KEYS[2]) or '0') "
                    + "local bytes = tonumber(ARGV[2]) "
                    + "local limit = tonumber(ARGV[3]) "
                    + "local newTotal = total - cur + bytes "
                    + "if limit > 0 and newTotal > limit then return 0 end "
                    + "redis.call('HSET', KEYS[1], ARGV[1], bytes) "
                    + "redis.call('SET', KEYS[2], newTotal) "
                    + "return 1";

    /** Removes the identifier's reservation and subtracts it from the total (floor 0). */
    private static final String RELEASE_SCRIPT =
            "local cur = tonumber(redis.call('HGET', KEYS[1], ARGV[1]) or '0') "
                    + "if cur > 0 then "
                    + "  redis.call('HDEL', KEYS[1], ARGV[1]) "
                    + "  local total = tonumber(redis.call('GET', KEYS[2]) or '0') "
                    + "  local newTotal = total - cur "
                    + "  if newTotal < 0 then newTotal = 0 end "
                    + "  redis.call('SET', KEYS[2], newTotal) "
                    + "end "
                    + "return 1";

    private final JedisPool pool;
    private final String hashKey;
    private final String totalKey;

    public RedisQuotaStore(JedisPool pool, String keyPrefix) {
        this.pool = Objects.requireNonNull(pool, "pool");
        String prefix = keyPrefix == null || keyPrefix.trim().isEmpty() ? DEFAULT_KEY_PREFIX : keyPrefix;
        this.hashKey = prefix + "usage";
        this.totalKey = prefix + "total";
    }

    /**
     * Convenience factory creating a {@link JedisPool} from host/port/password.
     */
    public static RedisQuotaStore create(String host, int port, String password, String keyPrefix) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(16);
        JedisPool pool = password == null || password.isEmpty()
                ? new JedisPool(config, host, port, Protocol.DEFAULT_TIMEOUT)
                : new JedisPool(config, host, port, Protocol.DEFAULT_TIMEOUT, password);
        return new RedisQuotaStore(pool, keyPrefix);
    }

    @Override
    public boolean tryReserve(String identifier, long bytes, long limitBytes) {
        if (limitBytes <= 0) {
            return true;
        }
        try (Jedis jedis = pool.getResource()) {
            Object result = jedis.eval(RESERVE_SCRIPT,
                    Arrays.asList(hashKey, totalKey),
                    Arrays.asList(identifier, String.valueOf(Math.max(0, bytes)), String.valueOf(limitBytes)));
            return "1".equals(String.valueOf(result));
        }
    }

    @Override
    public void release(String identifier) {
        try (Jedis jedis = pool.getResource()) {
            jedis.eval(RELEASE_SCRIPT, Arrays.asList(hashKey, totalKey), Collections.singletonList(identifier));
        }
    }

    @Override
    public long usedBytes() {
        try (Jedis jedis = pool.getResource()) {
            String total = jedis.get(totalKey);
            return total == null ? 0 : Long.parseLong(total);
        }
    }

    /**
     * Rebuilds the usage counter from the authoritative task store, correcting any drift.
     */
    public void reconcile(TaskStore taskStore) {
        Map<String, String> usage = new HashMap<>();
        long total = 0;
        for (UploadTask task : taskStore.list()) {
            long size = Math.max(0, task.isMerged() ? task.getFinalFileSize() : task.getFileSize());
            usage.put(task.getIdentifier(), String.valueOf(size));
            total += size;
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.del(hashKey);
            if (!usage.isEmpty()) {
                jedis.hset(hashKey, usage);
            }
            jedis.set(totalKey, String.valueOf(total));
        }
    }
}
