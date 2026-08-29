/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.store.redis;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Integration tests for {@link RedisCleanupLock} against a throwaway {@code redis:7} docker
 * container (skipped when Docker is unavailable).
 */
public class RedisCleanupLockTest {

    private static final String PREFIX = "upload:cleanup:test:";

    @Rule
    public RedisDockerRule redis = new RedisDockerRule();

    private JedisPool pool;

    @Before
    public void setUp() {
        pool = new JedisPool(redis.host(), redis.port());
        try (Jedis jedis = pool.getResource()) {
            jedis.flushDB();
        }
    }

    @After
    public void tearDown() {
        pool.close();
    }

    @Test
    public void singleHolderAcquiresAndReleases() {
        RedisCleanupLock lock = new RedisCleanupLock(pool, PREFIX, "owner-1", 60);
        assertTrue(lock.tryAcquire());
        lock.release();
        // After release the lock is free again.
        RedisCleanupLock second = new RedisCleanupLock(pool, PREFIX, "owner-2", 60);
        assertTrue(second.tryAcquire());
        second.release();
    }

    @Test
    public void secondHolderIsRejectedWhileHeld() {
        RedisCleanupLock first = new RedisCleanupLock(pool, PREFIX, "owner-1", 60);
        RedisCleanupLock second = new RedisCleanupLock(pool, PREFIX, "owner-2", 60);
        assertTrue(first.tryAcquire());
        // The lease is held -> the second instance must be told to skip this round.
        assertFalse(second.tryAcquire());
        first.release();
        // After release the second instance can acquire.
        assertTrue(second.tryAcquire());
        second.release();
    }

    @Test
    public void releaseOnlyClearsWhenOwnerMatches() {
        RedisCleanupLock first = new RedisCleanupLock(pool, PREFIX, "owner-1", 60);
        RedisCleanupLock second = new RedisCleanupLock(pool, PREFIX, "owner-2", 60);
        assertTrue(first.tryAcquire());
        // The non-holder must not remove the lease it does not own.
        second.release();
        assertFalse(second.tryAcquire()); // still held by owner-1
        first.release();
    }

    @Test
    public void ttlExpiresLease() throws Exception {
        RedisCleanupLock first = new RedisCleanupLock(pool, PREFIX, "owner-1", 1);
        assertTrue(first.tryAcquire());
        // After the TTL the lease auto-expires, so another instance can acquire.
        Thread.sleep(1600);
        RedisCleanupLock second = new RedisCleanupLock(pool, PREFIX, "owner-2", 60);
        assertTrue(second.tryAcquire());
        second.release();
    }

    @Test
    public void createFactoryBuildsWorkingLock() {
        RedisCleanupLock lock = RedisCleanupLock.create(redis.host(), redis.port(), null, PREFIX, 60);
        assertTrue(lock.tryAcquire());
        lock.release();
    }
}
