/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.store.redis;

import cn.chenxinjie.uploadfile.core.util.IdentifierLockHandle;
import cn.chenxinjie.uploadfile.core.util.IdentifierLockProvider;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.params.SetParams;

import java.util.Objects;
import java.util.UUID;

/**
 * Distributed {@link IdentifierLockProvider} backed by a per-identifier Redis {@code SET NX PX} key
 * (rc.7), so uploads/merges of the same identifier are serialized across instances that share the
 * same storage.
 *
 * <p>Each acquisition uses a unique owner token; release deletes the key only when this acquisition
 * still owns it. The key auto-expires after {@code ttlSeconds} so a crashed holder never blocks the
 * identifier forever. Acquisition waits up to {@code acquireTimeoutMillis} and then fails, so a
 * stuck lock surfaces as an error rather than hanging.</p>
 */
public class RedisIdentifierLockProvider implements IdentifierLockProvider {

    public static final String DEFAULT_KEY_PREFIX = "upload:lock:";

    private final JedisPool pool;
    private final String keyPrefix;
    private final int ttlMillis;
    private final long acquireTimeoutMillis;
    private final long retryMillis;

    public RedisIdentifierLockProvider(JedisPool pool, String keyPrefix, int ttlSeconds, long acquireTimeoutMillis) {
        this(pool, keyPrefix, ttlSeconds, acquireTimeoutMillis, 50L);
    }

    public RedisIdentifierLockProvider(JedisPool pool, String keyPrefix, int ttlSeconds,
                                       long acquireTimeoutMillis, long retryMillis) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.keyPrefix = keyPrefix == null || keyPrefix.trim().isEmpty() ? DEFAULT_KEY_PREFIX : keyPrefix;
        this.ttlMillis = Math.max(1, ttlSeconds) * 1000;
        this.acquireTimeoutMillis = Math.max(0, acquireTimeoutMillis);
        this.retryMillis = Math.max(1, retryMillis);
    }

    /**
     * Convenience factory creating a {@link JedisPool} from host/port/password.
     */
    public static RedisIdentifierLockProvider create(String host, int port, String password, String keyPrefix,
                                                     int ttlSeconds, long acquireTimeoutMillis) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(16);
        JedisPool pool = password == null || password.isEmpty()
                ? new JedisPool(config, host, port, Protocol.DEFAULT_TIMEOUT)
                : new JedisPool(config, host, port, Protocol.DEFAULT_TIMEOUT, password);
        return new RedisIdentifierLockProvider(pool, keyPrefix, ttlSeconds, acquireTimeoutMillis);
    }

    @Override
    public IdentifierLockHandle lock(String identifier) {
        String key = keyPrefix + identifier;
        String token = UUID.randomUUID().toString();
        long deadline = System.currentTimeMillis() + acquireTimeoutMillis;
        while (true) {
            try (Jedis jedis = pool.getResource()) {
                String result = jedis.set(key, token, SetParams.setParams().nx().px(ttlMillis));
                if ("OK".equals(result)) {
                    break;
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new IllegalStateException("Timed out acquiring identifier lock for: " + identifier);
            }
            try {
                Thread.sleep(retryMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while acquiring identifier lock for: " + identifier, e);
            }
        }
        return () -> {
            try (Jedis jedis = pool.getResource()) {
                // Only the current owner may release the lock.
                if (token.equals(jedis.get(key))) {
                    jedis.del(key);
                }
            }
        };
    }
}
