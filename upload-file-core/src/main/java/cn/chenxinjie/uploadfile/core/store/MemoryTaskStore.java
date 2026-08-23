package cn.chenxinjie.uploadfile.core.store;

import cn.chenxinjie.uploadfile.core.model.UploadTask;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的元数据存储。重启后丢失，适合单机、测试或不需要持久化的场景。
 */
public class MemoryTaskStore implements TaskStore {

    private final Map<String, UploadTask> tasks = new ConcurrentHashMap<>();

    @Override
    public Optional<UploadTask> get(String identifier) {
        return Optional.ofNullable(tasks.get(identifier));
    }

    @Override
    public void save(UploadTask task) {
        tasks.put(task.getIdentifier(), task);
    }

    @Override
    public boolean remove(String identifier) {
        return tasks.remove(identifier) != null;
    }

    @Override
    public Collection<UploadTask> list() {
        return tasks.values();
    }
}
