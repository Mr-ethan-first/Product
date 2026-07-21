package com.example.flinkcdcsync.common;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 链路追踪上下文，基于 SLF4J MDC 透传 traceId。
 *
 * @author 50707
 */
public final class TraceContext {

    public static final String TRACE_ID = "traceId";

    private TraceContext() {
    }

    public static String getTraceId() {
        String tid = MDC.get(TRACE_ID);
        if (tid == null) {
            tid = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            MDC.put(TRACE_ID, tid);
        }
        return tid;
    }

    public static void setTraceId(String traceId) {
        MDC.put(TRACE_ID, traceId);
    }

    public static void clear() {
        MDC.remove(TRACE_ID);
    }
}
