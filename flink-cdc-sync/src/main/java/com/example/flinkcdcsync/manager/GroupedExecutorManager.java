package com.example.flinkcdcsync.manager;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 分组线程池管理器：对不同数据库 / 表分组的写入线程池进行隔离。
 *
 * @author 50707
 */
@Slf4j
@Component
public class GroupedExecutorManager {

    private final Map<String, ExecutorService> EXECUTOR_GROUPS = new ConcurrentHashMap<>();
    private final AtomicInteger groupCounter = new AtomicInteger(0);

    public static String extractGroupKey(String dbUrl, int parallelism, Object tag) {
        return dbUrl + "@" + (tag == null ? "default" : tag.hashCode());
    }

    public synchronized ExecutorService getGroupedExecutor(String groupKey, int parallelism) {
        return EXECUTOR_GROUPS.computeIfAbsent(groupKey, k -> {
            ThreadFactory tf = new NamedThreadFactory("geo-sync-" + groupCounter.incrementAndGet());
            int core = Math.max(1, parallelism);
            return new ThreadPoolExecutor(core, core, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(1024), tf,
                    new ThreadPoolExecutor.CallerRunsPolicy());
        });
    }

    public synchronized void shutdownGroup(String groupKey) {
        ExecutorService ex = EXECUTOR_GROUPS.remove(groupKey);
        if (ex != null) {
            ex.shutdownNow();
            log.info("Shut down executor group {}", groupKey);
        }
    }

    public void shutdownAll() {
        EXECUTOR_GROUPS.forEach((k, ex) -> ex.shutdownNow());
        EXECUTOR_GROUPS.clear();
        log.info("All grouped executors shut down");
    }

    static class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger seq = new AtomicInteger(0);

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
