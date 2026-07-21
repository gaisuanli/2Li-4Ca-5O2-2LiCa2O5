package edu.gzhu.sitesafe.common;

import org.slf4j.MDC;

public final class TraceContext {
    private TraceContext() {}

    public static String currentId() {
        return MDC.get("traceId");
    }
}
