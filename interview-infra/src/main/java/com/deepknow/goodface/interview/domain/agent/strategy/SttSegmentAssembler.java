package com.deepknow.goodface.interview.domain.agent.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * STT 片段组装策略：管理 partial 缓冲与软端点，生成可提交的语义片段。
 */
public class SttSegmentAssembler {
    private static final Logger log = LoggerFactory.getLogger(SttSegmentAssembler.class);

    private final ScheduledExecutorService scheduler;
    private final int softEndpointMillis;
    private final int maxSegmentChars;
    private final boolean earlyCommitPunctuation;
    private final Consumer<String> onSegmentCommitted;
    private final Consumer<Exception> onError;

    private final StringBuilder partialBuffer = new StringBuilder(512);
    private ScheduledFuture<?> softEndpointTask;

    // 基础指标
    private int segmentsCommitted = 0;
    private int softEndpointFires = 0;
    private int earlyCommitFires = 0;
    private int totalPartialChars = 0;
    private int maxCommittedLen = 0;

    public SttSegmentAssembler(ScheduledExecutorService scheduler,
                               int softEndpointMillis,
                               int maxSegmentChars,
                               boolean earlyCommitPunctuation,
                               Consumer<String> onSegmentCommitted,
                               Consumer<Exception> onError) {
        this.scheduler = scheduler;
        this.softEndpointMillis = Math.max(300, softEndpointMillis);
        this.maxSegmentChars = Math.max(50, maxSegmentChars);
        this.earlyCommitPunctuation = earlyCommitPunctuation;
        this.onSegmentCommitted = onSegmentCommitted;
        this.onError = onError;
    }

    public void onPartial(String partial) {
        if (partial == null || partial.isEmpty()) return;
        totalPartialChars += partial.length();
        appendPartial(partial);
        if (shouldEarlyCommit(partialBuffer)) {
            cancelSoftEndpoint();
            commitByEarly();
        } else {
            rescheduleSoftEndpoint();
        }
    }

    public void cancel() {
        cancelSoftEndpoint();
    }

    public void clear() {
        partialBuffer.setLength(0);
    }

    private void rescheduleSoftEndpoint() {
        cancelSoftEndpoint();
        softEndpointTask = scheduler.schedule(() -> {
            try {
                commitBySoftEndpoint();
            } catch (Exception e) {
                if (onError != null) onError.accept(e);
            }
        }, softEndpointMillis, TimeUnit.MILLISECONDS);
    }

    private void cancelSoftEndpoint() {
        try {
            if (softEndpointTask != null) {
                softEndpointTask.cancel(false);
                softEndpointTask = null;
            }
        } catch (Exception ignored) {}
    }

    private void commitBySoftEndpoint() {
        softEndpointFires++;
        doCommit();
    }

    private void commitByEarly() {
        earlyCommitFires++;
        doCommit();
    }

    private void doCommit() {
        String segment = drainPartialBuffer();
        if (!segment.isEmpty()) {
            try {
                segmentsCommitted++;
                int len = segment.length();
                if (len > maxCommittedLen) maxCommittedLen = len;
                onSegmentCommitted.accept(segment);
            } catch (Exception e) {
                if (onError != null) onError.accept(e);
            }
        }
    }

    private void appendPartial(String partial) {
        if (partialBuffer.length() > 0) partialBuffer.append(' ');
        partialBuffer.append(partial.trim());
    }

    private String drainPartialBuffer() {
        String s = partialBuffer.toString().trim();
        partialBuffer.setLength(0);
        return s == null ? "" : s;
    }

    private boolean shouldEarlyCommit(StringBuilder buf) {
        if (buf == null) return false;
        if (buf.length() >= maxSegmentChars) return true;
        if (!earlyCommitPunctuation) return false;
        String s = buf.toString();
        return s.indexOf('？') >= 0 || s.indexOf('?') >= 0 || s.indexOf('。') >= 0 || s.indexOf('.') >= 0 || s.indexOf('!') >= 0 || s.indexOf('！') >= 0;
    }

    public Metrics getMetricsSnapshot() {
        return new Metrics(segmentsCommitted, softEndpointFires, earlyCommitFires, totalPartialChars, maxCommittedLen);
    }

    public static class Metrics {
        public final int segmentsCommitted;
        public final int softEndpointFires;
        public final int earlyCommitFires;
        public final int totalPartialChars;
        public final int maxCommittedLen;

        public Metrics(int segmentsCommitted, int softEndpointFires, int earlyCommitFires, int totalPartialChars, int maxCommittedLen) {
            this.segmentsCommitted = segmentsCommitted;
            this.softEndpointFires = softEndpointFires;
            this.earlyCommitFires = earlyCommitFires;
            this.totalPartialChars = totalPartialChars;
            this.maxCommittedLen = maxCommittedLen;
        }

        @Override
        public String toString() {
            return "Metrics{" +
                    "segmentsCommitted=" + segmentsCommitted +
                    ", softEndpointFires=" + softEndpointFires +
                    ", earlyCommitFires=" + earlyCommitFires +
                    ", totalPartialChars=" + totalPartialChars +
                    ", maxCommittedLen=" + maxCommittedLen +
                    '}';
        }
    }
}