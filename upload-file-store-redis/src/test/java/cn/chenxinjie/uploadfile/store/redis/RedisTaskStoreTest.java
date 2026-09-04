/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.store.redis;

import cn.chenxinjie.uploadfile.core.model.UploadTask;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Integration tests for {@link RedisTaskStore} against a throwaway {@code redis:7} docker container
 * (skipped when Docker is unavailable).
 */
public class RedisTaskStoreTest {

    private static final String PREFIX = "upload:task:test:";

    @Rule
    public RedisDockerRule redis = new RedisDockerRule();

    private JedisPool pool;
    private RedisTaskStore store;

    @Before
    public void setUp() {
        pool = new JedisPool(redis.host(), redis.port());
        store = new RedisTaskStore(pool, PREFIX, 0);
        try (Jedis jedis = pool.getResource()) {
            jedis.flushDB();
        }
    }

    @After
    public void tearDown() {
        pool.close();
    }

    private UploadTask sampleTask(String id) {
        UploadTask task = new UploadTask();
        task.setIdentifier(id);
        task.setFileName("demo.txt");
        task.setFileSize(100);
        task.setChunkSize(50);
        task.setChunkTotal(2);
        task.setUploadedChunks(new TreeSet<>());
        return task;
    }

    @Test
    public void saveGetRoundTrip() {
        UploadTask task = sampleTask("a1");
        task.markUploaded(0);
        store.save(task);

        Optional<UploadTask> loaded = store.get("a1");
        assertTrue(loaded.isPresent());
        assertEquals("a1", loaded.get().getIdentifier());
        assertEquals(1, loaded.get().uploadedCount());
        assertTrue(loaded.get().isUploaded(0));
    }

    @Test
    public void updateOverwrites() {
        UploadTask task = sampleTask("b1");
        task.markUploaded(0);
        store.save(task);
        task.markUploaded(1);
        store.save(task);

        assertEquals(2, store.get("b1").get().uploadedCount());
    }

    @Test
    public void removeDeletes() {
        store.save(sampleTask("c1"));
        assertTrue(store.remove("c1"));
        assertFalse(store.get("c1").isPresent());
        assertFalse(store.remove("c1"));
    }

    @Test
    public void listReturnsAllTasks() {
        store.save(sampleTask("d1"));
        store.save(sampleTask("d2"));
        assertEquals(2, store.list().size());
    }

    @Test
    public void listExcludesRemoved() {
        store.save(sampleTask("e1"));
        store.save(sampleTask("e2"));
        store.remove("e1");

        assertEquals(1, store.list().size());
        assertEquals("e2", store.list().iterator().next().getIdentifier());
    }

    @Test
    public void getUnknownIdentifierIsEmpty() {
        assertFalse(store.get("nope").isPresent());
    }

    @Test
    public void mergeFieldsSurviveRoundTrip() {
        UploadTask task = sampleTask("f1");
        task.setMergeState(UploadTask.MERGE_STATE_RUNNING);
        task.setMergeError(null);
        task.setMergeStartedAt(42L);
        store.save(task);

        UploadTask loaded = store.get("f1").get();
        assertEquals(UploadTask.MERGE_STATE_RUNNING, loaded.mergeState());
        assertEquals(42L, loaded.getMergeStartedAt());
    }

    @Test
    public void ttlExpiresRecords() throws Exception {
        RedisTaskStore ttlStore = new RedisTaskStore(pool, PREFIX + "ttl:", 1);
        ttlStore.save(sampleTask("g1"));
        assertTrue(ttlStore.get("g1").isPresent());

        TimeUnit.MILLISECONDS.sleep(1600);
        assertFalse(ttlStore.get("g1").isPresent());
        assertTrue(ttlStore.list().isEmpty());
    }

    @Test
    public void isolatedByKeyPrefix() {
        RedisTaskStore other = new RedisTaskStore(pool, PREFIX + "other:", 0);
        store.save(sampleTask("h1"));

        assertTrue(store.get("h1").isPresent());
        assertFalse(other.get("h1").isPresent());
    }

    @Test
    public void blankIdentifierIsTreatedAsAbsent() {
        assertFalse(store.get(null).isPresent());
        assertFalse(store.get("").isPresent());
    }

    @Test
    public void literalNullValueIsTreatedAsAbsent() {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(PREFIX + "nullval", "null");
        }
        assertFalse(store.get("nullval").isPresent());
    }

    @Test
    public void listSkipsCorruptRecords() {
        store.save(sampleTask("good1"));
        try (Jedis jedis = pool.getResource()) {
            jedis.set(PREFIX + "corrupt", "this is { not json");
            jedis.sadd(PREFIX + "index", "corrupt");
        }

        assertEquals(1, store.list().size());
        assertEquals("good1", store.list().iterator().next().getIdentifier());
    }

    @Test
    public void singleArgumentConstructorUsesDefaultPrefix() {
        RedisTaskStore defaults = new RedisTaskStore(pool);
        defaults.save(sampleTask("def1"));

        assertTrue(defaults.get("def1").isPresent());
        assertTrue(defaults.list().stream().anyMatch(t -> "def1".equals(t.getIdentifier())));
    }

    @Test
    public void createFactoryBuildsUsableStoreWithoutPassword() {
        RedisTaskStore created = RedisTaskStore.create(redis.host(), redis.port(), "", PREFIX + "fact:", 0);
        created.save(sampleTask("f1"));

        assertTrue(created.get("f1").isPresent());
        assertFalse(created.get("f2").isPresent());
    }

    @Test
    public void createFactoryAcceptsPasswordEvenWhenServerHasNone() {
        // Building the pool must not connect (no AUTH is attempted until the first command),
        // so the password branch of the factory can be exercised without a password-protected server.
        RedisTaskStore created = RedisTaskStore.create(redis.host(), redis.port(), "secret", PREFIX + "pw:", 0);
        assertNotNull(created);
    }
}
