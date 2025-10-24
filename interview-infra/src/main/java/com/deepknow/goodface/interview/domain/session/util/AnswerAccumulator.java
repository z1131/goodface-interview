package com.deepknow.goodface.interview.domain.session.util;

public class AnswerAccumulator {
    private final StringBuilder buffer = new StringBuilder(1024);

    public synchronized void append(String delta) {
        buffer.append(delta == null ? "" : delta);
    }

    /**
     * 获取当前累积内容并清空缓冲。
     */
    public synchronized String drain() {
        String out = buffer.toString();
        buffer.setLength(0);
        return out;
    }
}