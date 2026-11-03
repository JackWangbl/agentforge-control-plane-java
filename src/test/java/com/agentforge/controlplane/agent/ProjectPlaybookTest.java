package com.agentforge.controlplane.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectPlaybookTest {

    @Test
    void matchesTheStartupErrorsSeenOnADeveloperMachine() {
        assertThat(titles("docker: unknown command: docker compose")).contains("Docker 没有 Compose 插件");
        assertThat(titles("unknown flag: --build")).contains("Docker 没有 Compose 插件");
        assertThat(titles("sudo: apt-get: command not found")).contains("这台机器没有 apt-get");
        assertThat(titles("failed to connect to the docker API at unix:///Users/berlin/.colima/default/docker.sock: connect: no such file or directory"))
                .contains("Colima 没有在运行");
        assertThat(titles("lookup registry-1.docker.io on [::1]:53: read: connection refused"))
                .contains("Colima 虚拟机的 DNS 指向了没有服务的 [::1]:53");
        assertThat(titles("Head \"https://registry-1.docker.io/v2/library/mysql/manifests/8.4\": dial tcp 157.240.10.41:443: i/o timeout"))
                .contains("Docker Hub 的地址解析或连通性不可用")
                .doesNotContain("应用连不上 Compose 里的 MySQL");
        assertThat(titles("FATA[0000] unknown flag: --registry-mirror")).contains("当前 Colima 版本不认识 --registry-mirror");
        assertThat(titles("Communications link failure")).contains("应用连不上 Compose 里的 MySQL");
    }

    @Test
    void manualAndPromptStayAttachedToTheInspectTool() {
        assertThat(ProjectPlaybook.manual()).contains("docker-up.sh").contains("inspect_project");
        assertThat(ProjectPlaybook.agentPrompt()).contains("inspect_project");
        assertThat(ProjectPlaybook.match("页面打不开，但没有报错")).isEmpty();
    }

    private static java.util.List<String> titles(String symptom) {
        return ProjectPlaybook.match(symptom).stream().map(ProjectPlaybook.Entry::title).toList();
    }
}
