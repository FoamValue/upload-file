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

import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Distributed {@link IdentifierLockProvider} backed by a per-identifier Redis {@code SET NX PX} key
 * (rc.7), so uploads/merges of the same identifier are serialized across instances that share the
 * same storage.
 *
 * <p>Each acquisition uses a unique owner token; release deletes the key only when this acquisition
 * still owns it. The key auto-expires after {@code ttlMillis} so a crashed holder never blocks the
 * identifier forever. Acquisition waits up to {@code acquireTimeoutMillis} and then fails, so a
 * stuck lock surfaces as an error rather than hanging.</p>
 *
 * <h2>Lease renewal (rc.8, G18)</h2>
 *
 * <p>A fixed TTL is unsafe for a critical section that can outlive it (e.g. merging a very large
 * file on a slow disk): once the key expires, another instance can acquire the same identifier and
 * the two merges race. To close that gap the held lock is <b>renewed</b> by a lightweight watchdog
 * every {@code renewIntervalMillis} (default {@code ttl/3}), using a Lua script that extends the
 * TTL only while this acquisition still owns the key. If the key was lost (the holder stalled past
 * the TTL and another instance took over), renewal stops and logs a warning, leaving the new owner
 * undisturbed. {@link IdentifierLockHandle#close()} stops the watchdog before the owner-checked
 * delete.</p>
 */
public class RedisIdentifierLockProvider implements IdentifierLockProvider {

    public static final String DEFAULT_KEY_PREFIX = "upload:lock:";

    private static final Logger LOG = Logger.getLogger(RedisIdentifierLockProvider.class.getName());

    /** Extends the TTL only when the caller still owns the key (returns 1 on renewal, 0 otherwise). */
    private static final String RENEW_SCRIPT =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                    + "  return redis.call('PEXPIRE', KEYS[1], ARGV[2]) "
                    + "end "
                    + "return 0";

    /** Deletes the key only when the caller still owns it. */
    private static final String RELEASE_SCRIPT =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                    + "  return redis.call('DEL', KEYS[1]) "
                    + "end "
                    + "return 0";

    /**
     * A small shared daemon scheduler for every provider, so watchdogs never keep the JVM alive.
     * More than one thread so a renewal that blocks on a pooled Redis connection (or a burst of
     * concurrently held locks) cannot delay other leases' renewals and let them expire.
     */
    private static final int WATCHDOG_THREADS = 4;

    private static final ScheduledExecutorService WATCHDOG =
            Executors.newScheduledThreadPool(WATCHDOG_THREADS, new ThreadFactory() {
                private final AtomicInteger seq = new AtomicInteger();

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "upload-file-lock-renew-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });

    private final JedisPool pool;
    private final String keyPrefix;
    private final int ttlMillis;
    private final long acquireTimeoutMillis;
    private final long retryMillis;
    private final long renewIntervalMillis;

    public RedisIdentifierLockProvider(JedisPool pool, String keyPrefix, int ttlSeconds, long acquireTimeoutMillis) {
        this(pool, keyPrefix, ttlSeconds, acquireTimeoutMillis, 50L);
    }

    public RedisIdentifierLockProvider(JedisPool pool, String keyPrefix, int ttlSeconds,
                                       long acquireTimeoutMillis, long retryMillis) {
        this(pool, keyPrefix, ttlSeconds, acquireTimeoutMillis, retryMillis, 0L);
    }

    /**
     * Creates the provider with an explicit renewal interval (rc.8).
     *
     * @param renewIntervalMillis how often to extend the lease while held; {@code <= 0} selects the
     *                            default {@code ttl/3}
     */
    public RedisIdentifierLockProvider(JedisPool pool, String keyPrefix, int ttlSeconds,
                                       long acquireTimeoutMillis, long retryMillis, long renewIntervalMillis) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.keyPrefix = keyPrefix == null || keyPrefix.trim().isEmpty() ? DEFAULT_KEY_PREFIX : keyPrefix;
        this.ttlMillis = Math.max(1, ttlSeconds) * 1000;
        this.acquireTimeoutMillis = Math.max(0, acquireTimeoutMillis);
        this.retryMillis = Math.max(1, retryMillis);
        long configuredRenew = renewIntervalMillis > 0
                ? renewIntervalMillis
                : Math.max(1, this.ttlMillis / 3L);
        if (configuredRenew >= this.ttlMillis) {
            LOG.warning("upload-file.lock.renew-interval (" + configuredRenew + "ms) is not shorter than the "
                    + "lease ttl (" + this.ttlMillis + "ms); the lease may expire before it is renewed");
        }
        this.renewIntervalMillis = configuredRenew;
    }

    /**
     * Convenience factory creating a {@link JedisPool} from host/port/password.
     */
    public static RedisIdentifierLockProvider create(String host, int port, String password, String keyPrefix,
                                                     int ttlSeconds, long acquireTimeoutMillis) {
        return create(host, port, password, keyPrefix, ttlSeconds, acquireTimeoutMillis, 0L);
    }

    /**
     * Convenience factory with an explicit renewal interval (rc.8).
     */
    public static RedisIdentifierLockProvider create(String host, int port, String password, String keyPrefix,
                                                     int ttlSeconds, long acquireTimeoutMillis,
                                                     long renewIntervalMillis) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(16);
        JedisPool pool = password == null || password.isEmpty()
                ? new JedisPool(config, host, port, Protocol.DEFAULT_TIMEOUT)
                : new JedisPool(config, host, port, Protocol.DEFAULT_TIMEOUT, password);
        return new RedisIdentifierLockProvider(pool, keyPrefix, ttlSeconds, acquireTimeoutMillis, 50L,
                renewIntervalMillis);
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
        return new RenewingHandle(key, identifier, token);
    }

    /**
     * A held lock that keeps its Redis lease alive for as long as it is held, so a long critical
     * section (e.g. a large merge) cannot silently lose mutual exclusion (rc.8, G18).
     */
    private final class RenewingHandle implements IdentifierLockHandle {

        private final String key;
        private final String identifier;
        private final String token;
        private final AtomicBoolean released = new AtomicBoolean(false);
        /**
         * The watchdog task, assigned right after it is scheduled. Declared {@code volatile} (not
         * {@code final}) because scheduling captures {@code this} before the constructor returns, so
         * the task could in theory run before the assignment; a {@code final} field would risk a
         * visibility issue there.
         */
        private volatile ScheduledFuture<?> renewal;

        RenewingHandle(String key, String identifier, String token) {
            this.key = key;
            this.identifier = identifier;
            this.token = token;
            this.renewal = WATCHDOG.scheduleWithFixedDelay(this::renew,
                    renewIntervalMillis, renewIntervalMillis, TimeUnit.MILLISECONDS);
        }

        private void renew() {
            if (released.get()) {
                return;
            }
            try (Jedis jedis = pool.getResource()) {
                Object result = jedis.eval(RENEW_SCRIPT, Collections.singletonList(key),
                        Arrays.asList(token, String.valueOf(ttlMillis)));
                if (!"1".equals(String.valueOf(result))) {
                    // The lease was lost (holder stalled past the TTL and another instance took
                    // over); stop renewing so we never fight the new owner for the key.
                    LOG.warning("upload-file: identifier lock lease lost during renewal for " + identifier
                            + "; the lock is no longer held exclusively");
                    cancelRenewal();
                }
            } catch (RuntimeException e) {
                // A transient Redis error must not kill the watchdog thread; the next tick retries.
                LOG.log(Level.WARNING, "upload-file: identifier lock renewal failed for " + identifier, e);
            }
        }

        private void cancelRenewal() {
            ScheduledFuture<?> task = renewal;
            if (task != null) {
                task.cancel(false);
            }
        }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            cancelRenewal();
            try (Jedis jedis = pool.getResource()) {
                // Only the current owner may release the lock.
                jedis.eval(RELEASE_SCRIPT, Collections.singletonList(key),
                        Collections.singletonList(token));
            }
        }
    }
}
