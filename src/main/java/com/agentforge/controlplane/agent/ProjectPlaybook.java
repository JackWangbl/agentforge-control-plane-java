package com.agentforge.controlplane.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/** 本项目启动和运行时的对照说明。只根据报错原文匹配，不访问机器。 */
public final class ProjectPlaybook {

    public record Entry(String title, String action) {}

    private record Rule(Predicate<String> matches, Entry entry) {}

    private static final List<Rule> RULES = List.of(
            rule(text -> has(text, "unknown command: docker compose") || has(text, "unknown flag: --build"),
                    "Docker 没有 Compose 插件",
                    "docker compose version 能输出版本才算装好。没有 apt-get 时，把对应系统的 Compose 插件下载到 ~/.docker/cli-plugins/docker-compose 并 chmod +x。装好后再运行 ./scripts/docker-up.sh。"),
            rule(text -> has(text, "apt-get: command not found"),
                    "这台机器没有 apt-get",
                    "不要用 apt 安装 Compose。按 uname -s 和 uname -m 从 GitHub 的 docker/compose 发布页下载插件到 ~/.docker/cli-plugins/docker-compose。平时用 sudo docker 时，还要复制到 /usr/local/lib/docker/cli-plugins/。"),
            rule(text -> has(text, "colima") && has(text, "docker.sock") && (has(text, "no such file") || has(text, "failed to connect")),
                    "Colima 没有在运行",
                    "Docker 客户端指向 ~/.colima/default/docker.sock，但套接字不存在。在本机执行 colima start，看到 colima is running 后再运行 ./scripts/docker-up.sh。"),
            rule(text -> has(text, "[::1]:53") && has(text, "connection refused"),
                    "Colima 虚拟机的 DNS 指向了没有服务的 [::1]:53",
                    "在虚拟机里重写解析文件：colima ssh -- sudo sh -c 'rm -f /etc/resolv.conf; printf \"nameserver 223.5.5.5\\nnameserver 119.29.29.29\\n\" > /etc/resolv.conf'。再用 colima ssh -- getent hosts registry-1.docker.io 确认能打出 IP。"),
            rule(text -> has(text, "registry-1.docker.io") && (has(text, "i/o timeout") || has(text, "157.240")),
                    "Docker Hub 的地址解析或连通性不可用",
                    "157.240 开头的地址不是 Docker Hub。旧版 Colima 没有 --registry-mirror。编辑 ~/.colima/default/colima.yaml，在 docker: 下加入 registry-mirrors: [https://docker.1ms.run]，dns 写成 223.5.5.5 和 119.29.29.29，然后 colima stop && colima start。docker info 能看到 Registry Mirrors 后再运行 ./scripts/docker-up.sh。"),
            rule(text -> has(text, "unknown flag: --registry-mirror"),
                    "当前 Colima 版本不认识 --registry-mirror",
                    "不要加这个参数。把镜像源写进 ~/.colima/default/colima.yaml 的 docker.registry-mirrors，然后 colima stop && colima start。"),
            rule(text -> !has(text, "registry-1.docker.io") && !has(text, "failed to resolve reference")
                            && (has(text, "communications link failure") || has(text, "access denied for user")
                            || (has(text, "mysql") && has(text, "connection refused"))),
                    "应用连不上 Compose 里的 MySQL",
                    "先看 mysql 容器是否健康：docker compose ps。数据库密码来自 .docker-secrets/db_password，不要把文件内容贴出来。根密码只用于健康检查。改过密钥文件后需要重建 mysql 数据卷，否则旧密码还在。"));

    private ProjectPlaybook() {}

    public static List<Entry> match(String symptom) {
        String text = symptom == null ? "" : symptom;
        List<Entry> hits = new ArrayList<>();
        for (Rule rule : RULES) {
            if (rule.matches.test(text)) {
                hits.add(rule.entry);
            }
        }
        return hits;
    }

    public static String manual() {
        StringBuilder body = new StringBuilder();
        body.append("""
                # 项目排查手册

                用于排查 AgentForge Control Plane 这个仓库自己的启动和运行问题。先看用户贴出的原文，再对照下面的条目。不能声称已经修改了用户的电脑。

                启动入口是 ./scripts/docker-up.sh。它会在 .docker-secrets/ 写入 db_password 和 db_root_password（权限 600，不进 Git），然后执行 docker compose up --build。compose.yaml 里有 mysql:8.4 和由 Dockerfile 构建的 agentforge。页面在 http://127.0.0.1:8080。

                """);
        for (Rule rule : RULES) {
            body.append("## ").append(rule.entry.title()).append("\n\n");
            body.append(rule.entry.action()).append("\n\n");
        }
        body.append("没有命中上面的条目时，调用 inspect_project 查看本租户的最近会话、失败记录和落盘日志，再根据原文说明缺什么信息。\n");
        return body.toString().strip();
    }

    public static String agentPrompt() {
        return """
                你是 AgentForge 控制面的项目排查助手。用户贴出报错、启动失败或页面异常时，先调用 inspect_project，把原文放进 symptom。只根据工具结果和已绑定的排查手册下结论，不要编造已经执行过的修复。Colima、DNS 和镜像配置都在用户自己的电脑上，只能给出要复制执行的命令。回答用简体中文：先写原因，再给下一步。
                """.strip();
    }

    private static Rule rule(Predicate<String> matches, String title, String action) {
        return new Rule(matches, new Entry(title, action));
    }

    private static boolean has(String text, String needle) {
        return text.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
