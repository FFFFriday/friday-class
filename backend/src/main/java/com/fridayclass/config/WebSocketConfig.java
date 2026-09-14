package com.fridayclass.config;

import com.fridayclass.ws.PageWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 注册。
 *
 * <p>只注册一个端点：{@code /ws/page}，用于把老师翻到的页码推给学生。
 *
 * <p><b>关于 allowed origins</b>：这里用的是 {@code setAllowedOriginPatterns} 而不是
 * {@code setAllowedOrigins}，并且允许 localhost 的**任意端口**。
 * 原因：{@code SecurityConfig} 的 CORS 把来源写死成 {@code http://localhost:5173}，
 * 前端一换端口（5173 被占、或同时开两个 dev server）就会 403。
 * 这里不重复那个坑——但要记住：**部署到真实域名时，这两个地方都要改。**
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final PageWebSocketHandler pageWebSocketHandler;

    /**
     * 允许的来源模式，逗号分隔。默认只放行本机任意端口（开发足够）；
     * 生产用环境变量 {@code APP_WS_ALLOWED_ORIGIN_PATTERNS} 覆盖成真实域名。
     */
    @Value("${app.ws.allowed-origin-patterns:http://localhost:*,http://127.0.0.1:*}")
    private String[] allowedOriginPatterns;

    public WebSocketConfig(PageWebSocketHandler pageWebSocketHandler) {
        this.pageWebSocketHandler = pageWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(pageWebSocketHandler, "/ws/page")
                .setAllowedOriginPatterns(allowedOriginPatterns);
    }
}
