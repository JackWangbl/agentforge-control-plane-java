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
}
