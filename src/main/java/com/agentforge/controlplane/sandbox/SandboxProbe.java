package com.agentforge.controlplane.sandbox;

import com.agentforge.controlplane.domain.SandboxPolicy;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 沙箱试跑，对应 Python 版 sandbox_runtime.probe_sandbox：
 * 先跑一段 Python 看后端通不通，再故意连一次外网验证隔离是否真的生效。
 */
@Component
public class SandboxProbe {

    private final SandboxToolProvider sandbox;

    public SandboxProbe(SandboxToolProvider sandbox) {
        this.sandbox = sandbox;
    }

    /** 返回 {ready, backend, available_backends, sample, network_isolated, message, error}。 */
    public Map<String, Object> probeSandbox(SandboxPolicy row) {
        long tenantId = row == null || row.getTenantId() == null ? 0L : row.getTenantId();
        String executionId = "probe-" + (row == null || row.getId() == null ? 0L : row.getId());
        Map<String, Object> python = sandbox.executeInSandbox(
                row, "python", "print('sandbox-ok', 1+1)", tenantId, executionId);
        Map<String, Object> network = sandbox.executeInSandbox(
                row,
                "python",
                "import socket\nprint(socket.create_connection(('1.1.1.1', 53), 1))",
                tenantId,
                executionId);

        boolean denied = SandboxToolProvider.networkDenied(row);
        boolean pythonOk = Boolean.TRUE.equals(python.get("ok"));
        boolean networkBlocked = !Boolean.TRUE.equals(network.get("ok"));
        boolean ready = pythonOk && (!denied || networkBlocked);
        String name = row == null || row.getName() == null ? "" : row.getName();

        String message;
        if (denied && !networkBlocked) {
            message = name + " 代码能跑，但网络隔离未生效。";
        } else if (pythonOk) {
            message = name + " 已用 " + text(python.get("backend")) + " 后端跑通：" + text(python.get("output"));
        } else {
            String detail = text(python.get("error"));
            message = name + " 试跑失败：" + (detail.isEmpty() ? text(python.get("output")) : detail);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ready", ready);
        String backend = text(python.get("backend"));
        result.put("backend", backend.isEmpty() ? sandbox.preferredBackend(row) : backend);
        result.put("available_backends", sandbox.detectBackends());
        result.put("sample", text(python.get("output")));
        result.put("network_isolated", denied && networkBlocked);
        result.put("message", message);
        if (ready) {
            result.put("error", "");
        } else {
            String error = text(python.get("error"));
            result.put("error", error.isEmpty() ? text(network.get("error")) : error);
        }
        return result;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
