package com.selfanalyst.config;

import com.selfanalyst.ontology.Neo4jSyncConfig;
import java.net.URI;
import java.util.Properties;

/** 仅在读取同步状态或手工同步时解析；绝不读取或持久化密码值。 */
public final class Neo4jConfigResolver {
    private Neo4jConfigResolver() {}

    public static Neo4jSyncConfig from(Properties properties) {
        String enabled = value(properties, "enabled", "false");
        int timeout;
        try { timeout = Integer.parseInt(value(properties, "timeout-seconds", "15")); }
        catch (NumberFormatException invalid) { timeout = 0; }
        // 保留非法值的拒绝语义，而不在应用启动时解析此可选功能。
        if (!enabled.equals("true") && !enabled.equals("false")) timeout = 0;
        return new Neo4jSyncConfig(enabled.equals("true"), value(properties, "uri", ""),
                value(properties, "database", "neo4j"), value(properties, "username", "neo4j"),
                value(properties, "password-env", "SELF_ANALYST_NEO4J_PASSWORD"),
                value(properties, "namespace", ""), timeout);
    }

    private static String value(Properties properties, String key, String fallback) {
        return properties.getProperty("neo4j." + key, fallback).trim();
    }

    /** 防止错误粘贴的 URI 凭据进入有效配置/状态展示。原始编辑器仍保留用户文件。 */
    public static String publicValue(String key, String value) {
        if (!key.equals("neo4j.uri") || value.isBlank()) return value;
        try {
            URI uri = URI.create(value);
            if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getHost() == null || (uri.getPath() != null && !uri.getPath().isEmpty())) return "[invalid URI]";
            return value;
        } catch (IllegalArgumentException invalid) { return "[invalid URI]"; }
    }

    /** 拒绝把密码值误当成新配置项；密码只允许由指定环境变量提供。 */
    public static void rejectStoredSecrets(Properties properties) {
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("neo4j.") && !key.equals("neo4j.password-env")
                    && (key.toLowerCase(java.util.Locale.ROOT).contains("password")
                    || key.toLowerCase(java.util.Locale.ROOT).contains("secret"))) {
                throw new IllegalArgumentException("Neo4j password must use an environment variable");
            }
        }
        String passwordEnv = properties.getProperty("neo4j.password-env", "SELF_ANALYST_NEO4J_PASSWORD");
        if (!passwordEnv.isBlank() && !passwordEnv.trim().matches("[A-Z_][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("Neo4j password environment variable name is invalid");
        }
        String uri = properties.getProperty("neo4j.uri", "");
        // URI validation is deferred; saving embedded credentials is not allowed even while disabled.
        if (uri.contains("@") || uri.contains("?") || uri.contains("#")) {
            throw new IllegalArgumentException("Neo4j URI must not contain credentials, query or fragment");
        }
    }
}
