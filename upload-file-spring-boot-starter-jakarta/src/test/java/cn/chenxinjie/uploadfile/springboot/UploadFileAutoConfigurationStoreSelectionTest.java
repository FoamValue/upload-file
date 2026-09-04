/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.springboot;

import cn.chenxinjie.uploadfile.core.storage.LocalFileChunkStorage;
import cn.chenxinjie.uploadfile.core.store.FileTaskStore;
import cn.chenxinjie.uploadfile.core.store.MemoryTaskStore;
import cn.chenxinjie.uploadfile.store.jdbc.JdbcTaskStore;
import cn.chenxinjie.uploadfile.store.redis.RedisTaskStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.io.File;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-tests the metadata-store selection logic of {@link UploadFileAutoConfiguration} by
 * invoking the public bean methods directly (no Spring container needed).
 */
class UploadFileAutoConfigurationStoreSelectionTest {

    private final UploadFileAutoConfiguration config = new UploadFileAutoConfiguration();

    @Test
    void memoryStoreIsSelected() {
        UploadFileProperties props = new UploadFileProperties();
        props.setMetadataStore("memory");
        assertInstanceOf(MemoryTaskStore.class, config.uploadFileTaskStore(props, emptyProvider()));
    }

    @Test
    void fileStoreRequiresMetadataDir() {
        UploadFileProperties props = new UploadFileProperties();
        props.setMetadataStore("file");
        props.setMetadataDir("target/store-selection-meta");
        assertInstanceOf(FileTaskStore.class, config.uploadFileTaskStore(props, emptyProvider()));
    }

    @Test
    void fileStoreWithoutMetadataDirFails() {
        UploadFileProperties props = new UploadFileProperties();
        props.setMetadataStore("file");
        assertThrows(IllegalArgumentException.class,
                () -> config.uploadFileTaskStore(props, emptyProvider()));
    }

    @Test
    void jdbcStoreIsSelectedWhenDataSourceProvided() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:store-selection-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        UploadFileProperties props = new UploadFileProperties();
        props.setMetadataStore("jdbc");
        assertInstanceOf(JdbcTaskStore.class, config.uploadFileTaskStore(props, providerOf(dataSource)));
    }

    @Test
    void jdbcStoreFallsBackWithoutDataSource() {
        UploadFileProperties props = new UploadFileProperties();
        props.setMetadataStore("jdbc");
        assertInstanceOf(MemoryTaskStore.class, config.uploadFileTaskStore(props, emptyProvider()));
    }

    @Test
    void redisStoreIsSelectedWhenModulePresent() {
        UploadFileProperties props = new UploadFileProperties();
        props.setMetadataStore("redis");
        assertInstanceOf(RedisTaskStore.class, config.uploadFileTaskStore(props, emptyProvider()));
    }

    @Test
    void autoStoreWithMetadataDirSelectsFile() {
        UploadFileProperties props = new UploadFileProperties();
        props.setMetadataStore("auto");
        props.setMetadataDir("target/store-selection-auto-meta");
        assertInstanceOf(FileTaskStore.class, config.uploadFileTaskStore(props, emptyProvider()));
    }

    @Test
    void autoStoreWithoutMetadataDirSelectsMemory() {
        UploadFileProperties props = new UploadFileProperties();
        props.setMetadataStore("auto");
        assertInstanceOf(MemoryTaskStore.class, config.uploadFileTaskStore(props, emptyProvider()));
    }

    @Test
    void unknownStoreFallsBackToAutoBehavior() {
        UploadFileProperties props = new UploadFileProperties();
        props.setMetadataStore("bogus");
        assertInstanceOf(MemoryTaskStore.class, config.uploadFileTaskStore(props, emptyProvider()));
    }

    @Test
    void nullStoreFallsBackToAutoBehavior() {
        UploadFileProperties props = new UploadFileProperties();
        props.setMetadataStore(null);
        assertInstanceOf(MemoryTaskStore.class, config.uploadFileTaskStore(props, emptyProvider()));
    }

    @Test
    void chunkStorageUsesStorageDirChunksSubdirectory() {
        UploadFileProperties props = new UploadFileProperties();
        props.setStorageDir("target/store-selection-storage");
        assertInstanceOf(LocalFileChunkStorage.class, config.uploadFileChunkStorage(props));
        assertTrue(new File("target/store-selection-storage", "chunks").isDirectory());
    }

    private static ObjectProvider<DataSource> emptyProvider() {
        return new StubProvider<>(null);
    }

    private static ObjectProvider<DataSource> providerOf(DataSource dataSource) {
        return new StubProvider<>(dataSource);
    }

    /** Minimal {@link ObjectProvider} whose {@code getIfAvailable} returns the fixed value. */
    private static final class StubProvider<T> implements ObjectProvider<T> {
        private final T value;

        StubProvider(T value) {
            this.value = value;
        }

        @Override
        public T getObject() {
            return value;
        }

        @Override
        public T getObject(Object... args) {
            return value;
        }

        @Override
        public T getIfAvailable() {
            return value;
        }

        @Override
        public T getIfAvailable(Supplier<T> defaultSupplier) {
            return value != null ? value : defaultSupplier.get();
        }

        @Override
        public void ifAvailable(java.util.function.Consumer<T> dependencyConsumer) {
            if (value != null) {
                dependencyConsumer.accept(value);
            }
        }

        @Override
        public T getIfUnique() {
            return value;
        }

        @Override
        public T getIfUnique(Supplier<T> defaultSupplier) {
            return value != null ? value : defaultSupplier.get();
        }

        @Override
        public void ifUnique(java.util.function.Consumer<T> dependencyConsumer) {
            if (value != null) {
                dependencyConsumer.accept(value);
            }
        }

        @Override
        public Iterator<T> iterator() {
            return value != null ? Collections.singletonList(value).iterator() : Collections.emptyIterator();
        }

        @Override
        public Stream<T> stream() {
            return value != null ? Stream.of(value) : Stream.empty();
        }

        @Override
        public Stream<T> orderedStream() {
            return stream();
        }
    }
}
