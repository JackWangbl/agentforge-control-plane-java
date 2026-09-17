package com.agentforge.controlplane.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Python 版 .env 里是 SQLAlchemy URL（mysql+pymysql://user:pass@host/db），
 * Spring DataSource 要的是 JDBC。必须排在 config.import 读完 .env 之后，
 * 否则 ${DATABASE_URL} 会原样塞进 Hikari。
 */
public class SqlAlchemyUrlEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String raw = firstNonBlank(
                readDotEnv("DATABASE_URL"),
                environment.getProperty("DATABASE_URL"),
                environment.getProperty("spring.datasource.url"));
        if (raw == null) {
            return;
        }
        Parsed parsed = parse(raw);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("spring.datasource.url", parsed.jdbc);
        if (!parsed.username.isEmpty()) {
            props.put("spring.datasource.username", parsed.username);
        }
        props.put("spring.datasource.password", parsed.password);
        environment.getPropertySources().addFirst(new MapPropertySource("sqlalchemy-url", props));
        System.out.printf(
                "sqlalchemy-url -> jdbc=%s user=%s passwordLen=%d%n",
                parsed.jdbc, parsed.username, parsed.password.length());
    }

    static Parsed parse(String raw) {
        String url = raw.strip();
        if (url.startsWith("jdbc:")) {
            return new Parsed(sanitizeJdbc(url), "", "");
        }
        url = url.replaceFirst("^mysql\\+pymysql://", "mysql://")
                .replaceFirst("^mysql\\+mysqldb://", "mysql://")
                .replaceFirst("^mariadb\\+pymysql://", "mysql://");
        if (!url.startsWith("mysql://")) {
            throw new IllegalStateException("无法识别的 DATABASE_URL，需要 mysql+pymysql:// 或 jdbc:mysql://");
        }
        URI uri = URI.create(url);
        String userInfo = uri.getRawUserInfo() == null ? "" : uri.getRawUserInfo();
        String username = "";
        String password = "";
        int cut = userInfo.indexOf(':');
        if (cut >= 0) {
            username = decode(userInfo.substring(0, cut));
            password = decode(userInfo.substring(cut + 1));
        } else if (!userInfo.isEmpty()) {
            username = decode(userInfo);
        }
        String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 3306;
        String path = uri.getPath() == null ? "/agentforge" : uri.getPath();
        if (path.isEmpty() || "/".equals(path)) {
            path = "/agentforge";
        }
        Map<String, String> query = parseQuery(uri.getRawQuery());
        String charset = query.getOrDefault("charset", query.getOrDefault("characterEncoding", "utf8mb4"));
        query.remove("charset");
        if ("utf8mb4".equalsIgnoreCase(charset) || "utf8".equalsIgnoreCase(charset)) {
            query.put("characterEncoding", "UTF-8");
        } else {
            query.put("characterEncoding", charset);
        }
        query.putIfAbsent("connectionCollation", "utf8mb4_unicode_ci");
        query.putIfAbsent("serverTimezone", "UTC");
        query.putIfAbsent("allowPublicKeyRetrieval", "true");
        // MySQL 9 + Connector/J：sslMode=DISABLED / useSSL=false 会 1045。
        // 默认 PREFERRED，有证书就加密，否则再降级。
        query.remove("useSSL");
        query.putIfAbsent("sslMode", "PREFERRED");
        query.putIfAbsent("tcpKeepAlive", "true");
        StringBuilder jdbc = new StringBuilder("jdbc:mysql://")
                .append(host).append(':').append(port).append(path).append('?');
        boolean first = true;
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (!first) {
                jdbc.append('&');
            }
            first = false;
            jdbc.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return new Parsed(jdbc.toString(), username, password);
    }

    static String sanitizeJdbc(String jdbc) {
        String cleaned = jdbc.replace("useSSL=false", "sslMode=PREFERRED")
                .replace("useSSL=true", "sslMode=REQUIRED");
        if (!cleaned.contains("sslMode=")) {
            cleaned += (cleaned.contains("?") ? "&" : "?") + "sslMode=PREFERRED";
        }
        return cleaned;
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> query = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return query;
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                query.put(decode(part), "");
            } else {
                query.put(decode(part.substring(0, eq)), decode(part.substring(eq + 1)));
            }
        }
        return query;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String readDotEnv(String key) {
        Path path = Path.of(System.getProperty("user.dir", ".")).resolve(".env");
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String stripped = line.strip();
                if (stripped.isEmpty() || stripped.startsWith("#") || !stripped.contains("=")) {
                    continue;
                }
                int eq = stripped.indexOf('=');
                if (key.equals(stripped.substring(0, eq).strip())) {
                    String value = stripped.substring(eq + 1).strip();
                    if ((value.startsWith("\"") && value.endsWith("\""))
                            || (value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    return value;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    record Parsed(String jdbc, String username, String password) {}
}
