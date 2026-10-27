package com.agentforge.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 对应 Python 版 app/database.py 里的 Settings 以及散落在代码里的环境变量。 */
@ConfigurationProperties(prefix = "agentforge")
public class AppSettings {

    private String agentscopeStudioUrl = "";
    private String otelEndpoint = "";
    private Langfuse langfuse = new Langfuse();
    private String authDevUser = "";
    private String workspacesDir = "";
    private String ragFilesDir = "";
    private String sandboxesDir = "";
    private boolean allowUnsafeLocalSandbox = false;
    private String sandboxDefaultImage = "python:3.11-slim";
    private String sandboxAllowedImages = "";
    private String sandboxEgressNetwork = "";
    private boolean browserAllowPrivateNetwork = false;
    private boolean evalWorker = true;
    private Recovery recovery = new Recovery();
    private HttpRetry httpRetry = new HttpRetry();

    public static class Recovery {
        private long syncWaitMs = 3000;
        private long stepTimeoutMs = 300_000;
        private long overallTimeoutMs = 1_800_000;
        private int perAgentConcurrency = 2;
        private int perTenantConcurrency = 10;
        private int tenantQueueCapacity = 100;
        private int workerThreads = 4;
        private long pollIntervalMs = 500;
        private long leaseMs = 30_000;

        public long getSyncWaitMs() { return syncWaitMs; }
        public void setSyncWaitMs(long value) { syncWaitMs = bounded("sync-wait-ms", value, 0, 30_000); }
        public long getStepTimeoutMs() { return stepTimeoutMs; }
        public void setStepTimeoutMs(long value) { stepTimeoutMs = bounded("step-timeout-ms", value, 1_000, 3_600_000); }
        public long getOverallTimeoutMs() { return overallTimeoutMs; }
        public void setOverallTimeoutMs(long value) { overallTimeoutMs = bounded("overall-timeout-ms", value, 1_000, 86_400_000); }
        public int getPerAgentConcurrency() { return perAgentConcurrency; }
        public void setPerAgentConcurrency(int value) { perAgentConcurrency = boundedInt("per-agent-concurrency", value, 1, 32); }
        public int getPerTenantConcurrency() { return perTenantConcurrency; }
        public void setPerTenantConcurrency(int value) { perTenantConcurrency = boundedInt("per-tenant-concurrency", value, 1, 128); }
        public int getTenantQueueCapacity() { return tenantQueueCapacity; }
        public void setTenantQueueCapacity(int value) { tenantQueueCapacity = boundedInt("tenant-queue-capacity", value, 1, 10_000); }
        public int getWorkerThreads() { return workerThreads; }
        public void setWorkerThreads(int value) { workerThreads = boundedInt("worker-threads", value, 1, 64); }
        public long getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(long value) { pollIntervalMs = bounded("poll-interval-ms", value, 50, 60_000); }
        public long getLeaseMs() { return leaseMs; }
        public void setLeaseMs(long value) { leaseMs = bounded("lease-ms", value, 1_000, 600_000); }
    }

    public static class HttpRetry {
        private int totalAttempts = 3;
        private long initialDelayMs = 200;
        private double multiplier = 2.0;
        private long maximumDelayMs = 2000;
        private double jitter = 0.20;
        private long maximumRetryAfterMs = 5000;

        public int getTotalAttempts() { return totalAttempts; }
        public void setTotalAttempts(int value) { totalAttempts = boundedInt("total-attempts", value, 1, 5); }
        public long getInitialDelayMs() { return initialDelayMs; }
        public void setInitialDelayMs(long value) { initialDelayMs = bounded("initial-delay-ms", value, 0, 5_000); }
        public double getMultiplier() { return multiplier; }
        public void setMultiplier(double value) { multiplier = boundedDouble("multiplier", value, 1.0, 4.0); }
        public long getMaximumDelayMs() { return maximumDelayMs; }
        public void setMaximumDelayMs(long value) { maximumDelayMs = bounded("maximum-delay-ms", value, 0, 5_000); }
        public double getJitter() { return jitter; }
        public void setJitter(double value) { jitter = boundedDouble("jitter", value, 0, 0.5); }
        public long getMaximumRetryAfterMs() { return maximumRetryAfterMs; }
        public void setMaximumRetryAfterMs(long value) { maximumRetryAfterMs = bounded("maximum-retry-after-ms", value, 0, 30_000); }
    }

    private static long bounded(String name, long value, long min, long max) {
        if (value < min || value > max) throw new IllegalArgumentException(name + " 超出安全范围");
        return value;
    }

    private static int boundedInt(String name, int value, int min, int max) {
        if (value < min || value > max) throw new IllegalArgumentException(name + " 超出安全范围");
        return value;
    }

    private static double boundedDouble(String name, double value, double min, double max) {
        if (value < min || value > max) throw new IllegalArgumentException(name + " 超出安全范围");
        return value;
    }

    public static class Langfuse {
        private String publicKey = "";
        private String secretKey = "";
        private String host = "https://cloud.langfuse.com";
        private String baseUrl = "";
        private String projectId = "";

        public boolean isConfigured() {
            return !publicKey.isBlank() && !secretKey.isBlank();
        }

        public String getPublicKey() { return publicKey; }
        public void setPublicKey(String v) { this.publicKey = v == null ? "" : v; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String v) { this.secretKey = v == null ? "" : v; }
        public String getHost() { return host; }
        public void setHost(String v) { this.host = v == null ? "" : v; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v == null ? "" : v; }
        public String getProjectId() { return projectId; }
        public void setProjectId(String v) { this.projectId = v == null ? "" : v; }
    }

    /** Studio 地址留空时回落到本地默认端口，与 Python 版 DEFAULT_STUDIO_URL 一致。 */
    public String resolvedStudioUrl() {
        String url = agentscopeStudioUrl == null ? "" : agentscopeStudioUrl.strip();
        if (url.isBlank()) {
            return "http://127.0.0.1:3000";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public boolean isStudioConfigured() {
        return agentscopeStudioUrl != null && !agentscopeStudioUrl.isBlank();
    }

    public String getAgentscopeStudioUrl() { return agentscopeStudioUrl; }
    public void setAgentscopeStudioUrl(String v) { this.agentscopeStudioUrl = v == null ? "" : v; }
    public String getOtelEndpoint() { return otelEndpoint; }
    public void setOtelEndpoint(String v) { this.otelEndpoint = v == null ? "" : v; }
    public Langfuse getLangfuse() { return langfuse; }
    public void setLangfuse(Langfuse v) { this.langfuse = v == null ? new Langfuse() : v; }
    public String getAuthDevUser() { return authDevUser; }
    public void setAuthDevUser(String v) { this.authDevUser = v == null ? "" : v; }
    public String getWorkspacesDir() { return workspacesDir; }
    public void setWorkspacesDir(String v) { this.workspacesDir = v == null ? "" : v; }
    public String getRagFilesDir() { return ragFilesDir; }
    public void setRagFilesDir(String v) { this.ragFilesDir = v == null ? "" : v; }
    public String getSandboxesDir() { return sandboxesDir; }
    public void setSandboxesDir(String v) { this.sandboxesDir = v == null ? "" : v; }
    public boolean isAllowUnsafeLocalSandbox() { return allowUnsafeLocalSandbox; }
    public void setAllowUnsafeLocalSandbox(boolean v) { this.allowUnsafeLocalSandbox = v; }
    public String getSandboxDefaultImage() { return sandboxDefaultImage; }
    public void setSandboxDefaultImage(String v) { this.sandboxDefaultImage = v; }
    public String getSandboxAllowedImages() { return sandboxAllowedImages; }
    public void setSandboxAllowedImages(String v) { this.sandboxAllowedImages = v == null ? "" : v; }
    public String getSandboxEgressNetwork() { return sandboxEgressNetwork; }
    public void setSandboxEgressNetwork(String v) { this.sandboxEgressNetwork = v == null ? "" : v; }
    public boolean isBrowserAllowPrivateNetwork() { return browserAllowPrivateNetwork; }
    public void setBrowserAllowPrivateNetwork(boolean v) { this.browserAllowPrivateNetwork = v; }
    public boolean isEvalWorker() { return evalWorker; }
    public void setEvalWorker(boolean v) { this.evalWorker = v; }
    public Recovery getRecovery() { return recovery; }
    public void setRecovery(Recovery value) { recovery = value == null ? new Recovery() : value; }
    public HttpRetry getHttpRetry() { return httpRetry; }
    public void setHttpRetry(HttpRetry value) { httpRetry = value == null ? new HttpRetry() : value; }
}
