/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.store.redis;

import cn.chenxinjie.uploadfile.core.model.UploadTask;
import cn.chenxinjie.uploadfile.core.service.StorageCleanupService;
import cn.chenxinjie.uploadfile.core.storage.LocalFileChunkStorage;
import cn.chenxinjie.uploadfile.core.util.IdentifierLock;
import cn.chenxinjie.uploadfile.core.util.IdentifierLockHandle;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * rc.8 coverage for the Redis correctness closure (G16-G20): quota reconcile and cleanup reclaim,
 * lock lease renewal, atomic index migration and batched {@code list()}.
 */
public class RedisRc8Test {

    @Rule
    public RedisDockerRule redis = new RedisDockerRule();

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

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

    private static UploadTask task(String identifier, long size) {
        UploadTask task = new UploadTask();
        task.setIdentifier(identifier);
        task.setFileName("demo.bin");
        task.setFileSize(size);
        task.setChunkTotal(1);
        task.setUploadedChunks(new TreeSet<>());
        return task;
    }

    @Test
    public void quotaReconcileCorrectsBothUnderAndOverCount() {
        RedisTaskStore store = new RedisTaskStore(pool, "rc8:qstore:", 0);
        store.save(task("a", 100));
        store.save(task("b", 50));

        RedisQuotaStore quota = new RedisQuotaStore(pool, "rc8:q:");
        // Simulate drift in both directions: an under-count and a leaked reservation.
        try (Jedis jedis = pool.getResource()) {
            jedis.set("rc8:q:total", "9999");
            jedis.hset("rc8:q:usage", "ghost", "777");
        }

        quota.reconcile(store);

        assertEquals(150, quota.usedBytes());
        try (Jedis jedis = pool.getResource()) {
            assertEquals(null, jedis.hget("rc8:q:usage", "ghost"));
        }
    }

    @Test
    public void cleanupReclaimsQuotaOfMergedUnconfirmedTaskWhoseKeyExpired() throws Exception {
        RedisTaskStore store = new RedisTaskStore(pool, "rc8:clean:", 0);
        // The task key is gone (TTL expired / never confirmed) but the merged dir remains on disk.
        Path mergedDir = folder.getRoot().toPath().resolve("files");
        Path orphan = mergedDir.resolve("gone");
        Files.createDirectories(orphan);
        Files.write(orphan.resolve("demo.bin"), new byte[]{1, 2, 3});

        RedisQuotaStore quota = new RedisQuotaStore(pool, "rc8:cleanq:");
        assertTrue(quota.tryReserve("gone", 3, 100));
        assertEquals(3, quota.usedBytes());

        LocalFileChunkStorage chunks = new LocalFileChunkStorage(folder.getRoot().toPath().resolve("chunks"));
        StorageCleanupService cleanup = new StorageCleanupService(
                store, chunks, mergedDir.toFile(), 0L, true, new IdentifierLock());
        cleanup.setQuotaStore(quota);
        cleanup.cleanup();

        assertEquals(0, quota.usedBytes());
        assertFalse(Files.exists(orphan));
    }

    @Test
    public void lockRenewalKeepsLeaseBeyondTtlAndReleasesOnClose() throws Exception {
        RedisIdentifierLockProvider holder =
                new RedisIdentifierLockProvider(pool, "rc8:lock:", 1, 1000, 50L, 200L);
        RedisIdentifierLockProvider contender =
                new RedisIdentifierLockProvider(pool, "rc8:lock:", 1, 400, 50L, 200L);

        IdentifierLockHandle held = holder.lock("id");
        // Sleep well past the 1s TTL: without renewal the contender would win; with renewal it times out.
        Thread.sleep(2500);
        assertThrows(IllegalStateException.class, () -> contender.lock("id"));

        held.close();
        try (IdentifierLockHandle again = contender.lock("id")) {
            assertNotNull(again);
        }
    }

    @Test
    public void lockOwnerChangeDoesNotLetStaleHolderDeleteTheNewLock() throws Exception {
        RedisIdentifierLockProvider first =
                new RedisIdentifierLockProvider(pool, "rc8:owner:", 1, 200, 20L, 100000L);
        RedisIdentifierLockProvider second =
                new RedisIdentifierLockProvider(pool, "rc8:owner:", 1, 200, 20L, 100000L);

        IdentifierLockHandle stale = first.lock("id");
        // Simulate the lease expiring and the second instance taking over.
        try (Jedis jedis = pool.getResource()) {
            jedis.del("rc8:owner:id");
        }
        IdentifierLockHandle fresh = second.lock("id");
        // Closing the stale holder must not delete the new owner's lock.
        stale.close();
        try (Jedis jedis = pool.getResource()) {
            assertNotNull(jedis.get("rc8:owner:id"));
        }
        fresh.close();
    }

    @Test
    public void indexMigrationIsAtomicUnderConcurrentSave() throws Exception {
        RedisTaskStore store = new RedisTaskStore(pool, "rc8:migrate:", 0);
        int legacyCount = 200;
        try (Jedis jedis = pool.getResource()) {
            // rc.6-style plain SET index plus the matching task keys.
            for (int i = 0; i < legacyCount; i++) {
                String id = "legacy-" + i;
                jedis.set("rc8:migrate:" + id, "{\"identifier\":\"" + id + "\",\"fileName\":\"d\",\"fileSize\":1,\"chunkTotal\":1,\"uploadedChunks\":[]}");
                jedis.sadd("rc8:migrate:index", id);
            }
        }

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 200; i++) {
                    store.save(task("new-" + i, 1));
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        writer.start();
        start.countDown();

        Collection<UploadTask> listed = store.list();
        writer.join(10_000);

        assertEquals(null, failure.get());
        Set<String> ids = new HashSet<>();
        for (UploadTask t : listed) {
            ids.add(t.getIdentifier());
        }
        for (int i = 0; i < legacyCount; i++) {
            assertTrue("legacy-" + i + " must survive migration", ids.contains("legacy-" + i));
        }
    }

    @Test
    public void listReadsLargeIndexInBatches() {
        RedisTaskStore store = new RedisTaskStore(pool, "rc8:list:", 0);
        int count = 1100; // > 2 MGET batches of 500
        for (int i = 0; i < count; i++) {
            store.save(task("id-" + i, 1));
        }
        assertEquals(count, store.list().size());
    }

    @Test
    public void quotaWithNonPositiveLimitIsDisabledAndEmptyStoreReadsZero() {
        RedisQuotaStore quota = new RedisQuotaStore(pool, "rc8:nolimit:");
        assertTrue(quota.tryReserve("a", 100, 0));
        assertTrue(quota.tryReserve("a", 100, -5));
        assertEquals(0, quota.usedBytes()); // nothing was recorded
    }

    @Test
    public void lockUsesDefaultRenewIntervalWhenNotConfigured() throws Exception {
        // 5-arg constructor: renew interval defaults to ttl/3 (1s ttl -> ~333ms).
        RedisIdentifierLockProvider holder = new RedisIdentifierLockProvider(pool, "rc8:default:", 1, 1000, 50L);
        RedisIdentifierLockProvider contender = new RedisIdentifierLockProvider(pool, "rc8:default:", 1, 300, 50L);
        IdentifierLockHandle held = holder.lock("id");
        Thread.sleep(2200); // more than two TTLs
        assertThrows(IllegalStateException.class, () -> contender.lock("id"));
        held.close();
    }

    @Test
    public void renewalAfterLeaseLossStopsWithoutError() throws Exception {
        RedisIdentifierLockProvider holder =
                new RedisIdentifierLockProvider(pool, "rc8:lost:", 1, 1000, 50L, 100L);
        IdentifierLockHandle held = holder.lock("id");
        // Drop the key behind the holder's back; the next renewal tick sees it lost and stops.
        try (Jedis jedis = pool.getResource()) {
            jedis.del("rc8:lost:id");
        }
        Thread.sleep(400);
        // The stale handle must still be safe to close, and a new owner can acquire.
        held.close();
        RedisIdentifierLockProvider contender = new RedisIdentifierLockProvider(pool, "rc8:lost:", 1, 500, 50L, 100L);
        try (IdentifierLockHandle again = contender.lock("id")) {
            assertNotNull(again);
        }
    }

    @Test
    public void convenienceFactoriesProduceWorkingStores() {
        RedisQuotaStore quota = RedisQuotaStore.create(redis.host(), redis.port(), null, "rc8:factoryq:");
        assertTrue(quota.tryReserve("a", 10, 100));
        assertEquals(10, quota.usedBytes());
        quota.release("a");
        assertEquals(0, quota.usedBytes());

        RedisIdentifierLockProvider provider = RedisIdentifierLockProvider.create(
                redis.host(), redis.port(), null, "rc8:factoryl:", 2, 500, 500);
        try (IdentifierLockHandle handle = provider.lock("a")) {
            assertNotNull(handle);
        }
    }

    @Test
    public void migrationIsIdempotentAndHandlesAnAlreadySortedIndex() {
        RedisTaskStore store = new RedisTaskStore(pool, "rc8:already:", 0);
        store.save(task("a", 1));
        // The first list() runs the migration script (TYPE=zset, so it is a no-op) and the second
        // call must still see the entry (the per-JVM fast path must not skip a needed migration).
        assertEquals(1, store.list().size());
        assertEquals(1, store.list().size());
    }

    @Test
    public void reconcileWithEmptyTaskStoreClearsTheCounter() {
        RedisTaskStore store = new RedisTaskStore(pool, "rc8:empty:", 0);
        RedisQuotaStore quota = new RedisQuotaStore(pool, "rc8:emptyq:");
        assertTrue(quota.tryReserve("stale", 42, 100));
        assertEquals(42, quota.usedBytes());

        quota.reconcile(store);
        assertEquals(0, quota.usedBytes());
        // Idempotent: a second reconcile keeps it at zero.
        quota.reconcile(store);
        assertEquals(0, quota.usedBytes());
    }

    @Test
    public void listRemainsConsistentWithinOnePass() throws Exception {
        RedisTaskStore store = new RedisTaskStore(pool, "rc8:snap:", 0);
        for (int i = 0; i < 50; i++) {
            store.save(task("s-" + i, 1));
        }
        Collection<UploadTask> listed = store.list();
        assertEquals(50, listed.size());
        // A second pass sees the same set.
        assertEquals(50, store.list().size());
        TimeUnit.MILLISECONDS.sleep(1);
    }
}
