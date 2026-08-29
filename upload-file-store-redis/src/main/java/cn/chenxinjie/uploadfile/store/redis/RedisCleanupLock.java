/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.store.redis;

import cn.chenxinjie.uploadfile.core.util.CleanupLock;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.params.SetParams;

import java.util.Objects;
import java.util.UUID;

/**
 * {@link CleanupLock} backed by a Redis {@code SET NX EX} key, so multiple instances share a
 * single lease. The lease auto-expires after {@code ttlSeconds} so a crashed holder never blocks
 * cleanup forever; {@code release()} removes the key only when this instance still owns it.
 */
public class RedisCleanupLock implements CleanupLock {

    private final JedisPool pool;
    private final String key;
    private final String owner;
    private final int ttlSeconds;

    private volatile boolean held;

    public RedisCleanupLock(JedisPool pool, String keyPrefix, int ttlSeconds) {
        this(pool, keyPrefix, UUID.randomUUID().toString(), ttlSeconds);
    }

    public RedisCleanupLock(JedisPool pool, String keyPrefix, String owner, int ttlSeconds) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.key = (keyPrefix == null || keyPrefix.trim().isEmpty() ? "upload:cleanup:lock" : keyPrefix) + "lock";
        this.owner = owner == null ? UUID.randomUUID().toString() : owner;
        this.ttlSeconds = Math.max(1, ttlSeconds);
    }

    /**
     * Convenience factory creating a {@link JedisPool} from host/port/password, mirroring
     * {@link RedisTaskStore#create(String, int, String, String, int)}.
     */
    public static RedisCleanupLock create(String host, int port, String password, String keyPrefix, int ttlSeconds) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(16);
        JedisPool pool = password == null || password.isEmpty()
                ? new JedisPool(config, host, port, Protocol.DEFAULT_TIMEOUT)
                : new JedisPool(config, host, port, Protocol.DEFAULT_TIMEOUT, password);
        return new RedisCleanupLock(pool, keyPrefix, ttlSeconds);
    }

    @Override
    public synchronized boolean tryAcquire() {
        try (Jedis jedis = pool.getResource()) {
            String result = jedis.set(key, owner, SetParams.setParams().nx().ex(ttlSeconds));
            held = "OK".equals(result);
            return held;
        }
    }

    @Override
    public synchronized void release() {
        if (!held) {
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            // Only the current holder may delete the lease.
            if (owner.equals(jedis.get(key))) {
                jedis.del(key);
            }
        } finally {
            held = false;
        }
    }
}
