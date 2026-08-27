/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.store.redis;

import org.junit.AssumptionViolatedException;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import redis.clients.jedis.Jedis;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * JUnit 4 rule that starts a throwaway {@code redis:7} container via the local Docker daemon
 * (see {@code docker images redis:7}) for the Redis integration tests.
 *
 * <p>When Docker is unavailable the tests are skipped (assumption violation) instead of failing.</p>
 */
public final class RedisDockerRule implements TestRule {

    private static final String CONTAINER_NAME = "upload-file-redis-test";
    private static final long PING_TIMEOUT_MILLIS = 60_000;

    private String host = "localhost";
    private int port;
    private String lastOutput = "";

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                if (!isDockerAvailable()) {
                    throw new AssumptionViolatedException("Docker is not available; skipping Redis integration test");
                }
                startContainer();
                try {
                    base.evaluate();
                } finally {
                    stopContainer();
                }
            }
        };
    }

    private boolean isDockerAvailable() {
        try {
            return exec("docker", "version", "--format", "{{.Server.Version}}") == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void startContainer() {
        try {
            exec("docker", "rm", "-f", CONTAINER_NAME); // clean up any leftover container
            int runRc = exec("docker", "run", "-d", "--rm", "--name", CONTAINER_NAME, "-p", "6379", "redis:7");
            if (runRc != 0) {
                throw new AssumptionViolatedException("Failed to start redis:7 container: " + lastOutput);
            }
            int portRc = exec("docker", "port", CONTAINER_NAME, "6379");
            if (portRc != 0) {
                throw new AssumptionViolatedException("Failed to resolve redis port: " + lastOutput);
            }
            port = parseHostPort(lastOutput);
            waitForPing();
        } catch (AssumptionViolatedException e) {
            stopContainer();
            throw e;
        } catch (Exception e) {
            stopContainer();
            throw new AssumptionViolatedException("Redis docker setup failed", e);
        }
    }

    private void stopContainer() {
        try {
            exec("docker", "rm", "-f", CONTAINER_NAME);
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    private void waitForPing() throws InterruptedException {
        long deadline = System.currentTimeMillis() + PING_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            try (Jedis jedis = new Jedis(host, port)) {
                if ("PONG".equals(jedis.ping())) {
                    return;
                }
            } catch (Exception ignored) {
                // container still booting
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
        throw new AssumptionViolatedException("redis:7 container did not become reachable in time");
    }

    /**
     * Parses the host port from {@code docker port <container> 6379} output, e.g.
     * {@code "0.0.0.0:32768"} or {@code "[::]:32768"}.
     */
    private static int parseHostPort(String output) {
        int colon = output.lastIndexOf(':');
        String candidate = output.substring(colon + 1).trim();
        try {
            return Integer.parseInt(candidate);
        } catch (NumberFormatException e) {
            throw new AssumptionViolatedException("Cannot parse docker port mapping: " + output);
        }
    }

    private int exec(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        lastOutput = sb.toString();
        return process.waitFor();
    }
}
