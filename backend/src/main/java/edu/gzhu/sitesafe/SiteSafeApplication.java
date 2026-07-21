package edu.gzhu.sitesafe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class SiteSafeApplication {
    public static void main(String[] args) {
        // 配置 JVM HTTP/HTTPS 代理（访问 DeepSeek 等外部 API 必需）
        // 在代码里设置以避免 Windows 命令行下 http.nonProxyHosts 的 | 转义问题
        configureProxy();
        SpringApplication.run(SiteSafeApplication.class, args);
    }

    /**
     * 配置 JVM 代理参数。
     * 支持通过环境变量 PROXY_HOST / PROXY_PORT / PROXY_NON_PROXY_HOSTS 覆盖默认值，
     * 默认使用 127.0.0.1:1088，本地连接绕过。
     * 也可通过 JVM 系统属性 -Dhttp.proxyHost 等完全禁用此机制。
     */
    private static void configureProxy() {
        String host = System.getProperty("http.proxyHost",
                System.getenv().getOrDefault("PROXY_HOST", "127.0.0.1"));
        String port = System.getProperty("http.proxyPort",
                System.getenv().getOrDefault("PROXY_PORT", "1088"));
        String nonProxyHosts = System.getProperty("http.nonProxyHosts",
                System.getenv().getOrDefault("PROXY_NON_PROXY_HOSTS", "localhost|127.0.0.1|::1"));

        // 仅当未显式设置 -Dhttp.proxyHost=none 时才配置
        if ("none".equalsIgnoreCase(host)) {
            return;
        }
        System.setProperty("http.proxyHost", host);
        System.setProperty("http.proxyPort", port);
        System.setProperty("https.proxyHost", host);
        System.setProperty("https.proxyPort", port);
        System.setProperty("http.nonProxyHosts", nonProxyHosts);
        System.setProperty("https.nonProxyHosts", nonProxyHosts);
    }
}
