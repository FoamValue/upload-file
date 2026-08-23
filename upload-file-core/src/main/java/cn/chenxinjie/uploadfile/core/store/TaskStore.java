package cn.chenxinjie.uploadfile.core.store;

import cn.chenxinjie.uploadfile.core.model.UploadTask;

import java.util.Collection;
import java.util.Optional;

/**
 * 上传任务元数据存储 SPI。
 *
 * <p>内置实现：{@link MemoryTaskStore}（内存）、{@link FileTaskStore}（本地文件 + JSON）。</p>
 * <p>可通过实现本接口接入 Redis、数据库等其它存储。</p>
 */
public interface TaskStore {

    Optional<UploadTask> get(String identifier);

    void save(UploadTask task);

    boolean remove(String identifier);

    Collection<UploadTask> list();
}
