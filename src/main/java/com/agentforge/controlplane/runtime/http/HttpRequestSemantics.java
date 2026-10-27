package com.agentforge.controlplane.runtime.http;

public enum HttpRequestSemantics {
    SAFE,
    IDEMPOTENT,
    SIDE_EFFECTING_OR_UNKNOWN
}
