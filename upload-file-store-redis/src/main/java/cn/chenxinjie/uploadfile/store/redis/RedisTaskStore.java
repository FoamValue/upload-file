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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Redis-backed {@link TaskStore} based on Jedis. Each task is stored as a string key
 * {@code <keyPrefix><identifier>} holding the JSON metadata; the identifiers are tracked in a
 * {@code <keyPrefix>index} set so {@link #list()} can enumerate them.
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
            if (ttlSeconds > 0) {
                jedis.setex(key(task.getIdentifier()), ttlSeconds, json);
            } else {
                jedis.set(key(task.getIdentifier()), json);
            }
            jedis.sadd(indexKey, task.getIdentifier());
        }
    }

    @Override
    public boolean remove(String identifier) {
        StringUtil.requireSafeIdentifier(identifier);
        try (Jedis jedis = pool.getResource()) {
            long removed = jedis.del(key(identifier));
            jedis.srem(indexKey, identifier);
            return removed > 0;
        }
    }

    @Override
    public Collection<UploadTask> list() {
        List<UploadTask> result = new ArrayList<>();
        try (Jedis jedis = pool.getResource()) {
            Set<String> identifiers = jedis.smembers(indexKey);
            for (String identifier : identifiers) {
                String json = jedis.get(key(identifier));
                if (json == null) {
                    continue;
                }
                try {
                    UploadTask task = gson.fromJson(json, UploadTask.class);
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
