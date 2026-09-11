/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.store.redis;

import cn.chenxinjie.uploadfile.core.model.UploadTask;
import cn.chenxinjie.uploadfile.core.util.IdentifierLockHandle;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * rc.7 coverage for {@link RedisQuotaStore} and {@link RedisIdentifierLockProvider} against a
 * throwaway {@code redis:7} container.
 */
public class RedisRc7Test {

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
    public void quotaStoreEnforcesLimitAtomically() {
        RedisQuotaStore quota = new RedisQuotaStore(pool, "test:quota:");
        assertTrue(quota.tryReserve("a", 100, 150));
        assertFalse(quota.tryReserve("b", 100, 150)); // 200 > 150
        assertEquals(100, quota.usedBytes());

        // replacing an existing identifier's reservation does not double count
        assertTrue(quota.tryReserve("a", 120, 150));
        assertEquals(120, quota.usedBytes());

        quota.release("a");
        assertEquals(0, quota.usedBytes());
        assertTrue(quota.tryReserve("b", 100, 150));
    }

    @Test
    public void quotaReconcileRebuildsFromTaskStore() {
        RedisTaskStore store = new RedisTaskStore(pool, "test:qstore:", 0);
        UploadTask task = new UploadTask();
        task.setIdentifier("x");
        task.setFileName("demo.txt");
        task.setFileSize(42);
        task.setChunkTotal(1);
        task.setUploadedChunks(new TreeSet<>());
        store.save(task);

        RedisQuotaStore quota = new RedisQuotaStore(pool, "test:q:");
        quota.reconcile(store);
        assertEquals(42, quota.usedBytes());
    }

    @Test
    public void identifierLockSerializesAcrossProviders() {
        RedisIdentifierLockProvider first = new RedisIdentifierLockProvider(pool, "test:lock:", 30, 1000);
        RedisIdentifierLockProvider second = new RedisIdentifierLockProvider(pool, "test:lock:", 30, 250);

        IdentifierLockHandle held = first.lock("id");
        // The second provider must time out while the first still owns the lock.
        assertThrows(IllegalStateException.class, () -> second.lock("id"));
        held.close();

        try (IdentifierLockHandle again = second.lock("id")) {
            assertNotNull(again);
        }
    }
}
