package com.agentforge.controlplane.rag;

/** 向量库或重排序调用失败。unavailable 为 true 时接口返回 503。 */
public class RagCallException extends RuntimeException {

    private final boolean unavailable;

    public RagCallException(String message, boolean unavailable) {
        super(message);
        this.unavailable = unavailable;
    }

    public boolean isUnavailable() {
        return unavailable;
    }
}
