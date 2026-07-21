package com.example.remotedatasync.manager;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 表级队列管理器：为每个表维护一个有界阻塞队列，用于异步缓冲待写入的变更事件。
 *
 * @author 50707
 */
@Slf4j
@Component
public class TableLevelQueueManager {

    /** 表队列：key = instanceKey.tableName */
    public final ConcurrentHashMap<String, TableQueue> TABLE_QUEUES = new ConcurrentHashMap<>();

    public TableQueue getOrCreateTableQueue(String key, int maxSize) {
        return TABLE_QUEUES.computeIfAbsent(key, k -> new TableQueue(maxSize));
    }

    public TableQueue getTableQueue(String key) {
        return TABLE_QUEUES.get(key);
    }

    public void removeTableQueue(String key) {
        TableQueue q = TABLE_QUEUES.remove(key);
        if (q != null) {
            q.queue.clear();
        }
    }

    /** 清理某个数据库实例下的所有表队列（key 以 instanceKey 前缀开头） */
    public void removeAllQueuesForDatabase(String instanceKeyPrefix) {
        List<String> toRemove = new ArrayList<>();
        for (String key : TABLE_QUEUES.keySet()) {
            if (key.startsWith(instanceKeyPrefix)) {
                toRemove.add(key);
            }
        }
        toRemove.forEach(this::removeTableQueue);
        if (!toRemove.isEmpty()) {
            log.info("Removed {} table queues for prefix {}", toRemove.size(), instanceKeyPrefix);
        }
    }

    /** 队列封装，提供批量抽取能力 */
    public static class TableQueue {
        private final LinkedBlockingQueue<Object> queue;
        private final int maxSize;

        public TableQueue(int maxSize) {
            this.maxSize = Math.max(100, maxSize);
            this.queue = new LinkedBlockingQueue<>(this.maxSize);
        }

        /** 反压：队列满时返回 false */
        public boolean offer(Object e) {
            return queue.offer(e);
        }

        /** 抽取最多 maxElements 个元素，不阻塞 */
        public List<Object> drain(int maxElements) {
            List<Object> batch = new ArrayList<>(Math.min(maxElements, queue.size()));
            queue.drainTo(batch, maxElements);
            return batch;
        }

        public int size() {
            return queue.size();
        }

        /** 清空队列（关闭时调用） */
        public void clear() {
            queue.clear();
        }

        public boolean isEmpty() {
            return queue.isEmpty();
        }

        /** 阻塞等待并取出一个元素（WAL 重试 / 健康检查用） */
        public Object poll(long timeout, TimeUnit unit) throws InterruptedException {
            return queue.poll(timeout, unit);
        }
    }
}
