package com.fridayclass.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fridayclass.common.ApiResponse;
import com.fridayclass.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.List;

/**
 * 安全配置：无状态 JWT 认证，拒绝优先（白名单之外一律需要登录）。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter, ObjectMapper objectMapper) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.objectMapper = objectMapper;
    }

    /**
     * BCrypt 强度 10（Spring 默认，OWASP 认可的下限）。
     * 强度每 +1 计算耗时翻倍，10 在安全与本地开发响应速度之间较平衡。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.sameOrigin())
                        .referrerPolicy(rp -> rp.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(auth -> auth
                        // 注册 / 登录：匿名可访问
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        // 上传课件：必须在这里就限教师角色。
                        // 不能只靠 Controller 上的 @PreAuthorize——那时 multipart 已解析完、
                        // 文件已写入磁盘，学生可借此反复上传撑爆磁盘。
                        .requestMatchers(HttpMethod.POST, "/api/courseware/upload").hasRole("TEACHER")
                        // 触发 AI 解析：仅教师。这个接口会让服务端真的调用**付费**模型
                        // （一份 79 页课件约 0.8 元），绝不能让学生或未登录者触发。
                        // 解析本身在后台异步跑，但这一步是花钱的闸门，必须卡死。
                        .requestMatchers(HttpMethod.POST, "/api/courseware/*/parse").hasRole("TEACHER")
                        // 门户课件公开视图：只放行契约中明确公开的 3 个路径，
                        // 不用 /api/courseware/** 整棵子树——否则将来新增的 GET
                        // （如原始 .pptx 下载、导出）会被一并匿名暴露。
                        // 注意 /{id}/prompt-pack 与 /{id}/parse-progress **不在**这里：
                        // 它们含知识点内容（课堂内部资料），需登录才能看。
                        .requestMatchers(HttpMethod.GET,
                                "/api/courseware",
                                "/api/courseware/{id}",
                                "/api/courseware/{id}/pages").permitAll()
                        // 网页幻灯片：随门户一起公开（课件详情页要能直接看）。
                        // 返回时统一加 sandbox CSP 响应头，限制其脚本能力。
                        .requestMatchers(HttpMethod.GET, "/slides/**").permitAll()
                        // 开课 / 翻页：限教师。「翻的是不是自己的课堂」由 Service 再校验一次。
                        .requestMatchers(HttpMethod.POST, "/api/session").hasRole("TEACHER")
                        .requestMatchers(HttpMethod.POST, "/api/session/*/page").hasRole("TEACHER")
                        // 下课：「结的是不是自己的课堂」同样由 Service 再校验一次。
                        // 「我的课堂」列表（GET /api/session/mine）不在这里放行——
                        // 它默认就需要登录，且只返回 principal 自己的课，不需要角色限制。
                        .requestMatchers(HttpMethod.POST, "/api/session/*/end").hasRole("TEACHER")
                        // WebSocket 握手：浏览器原生 WebSocket **无法携带 Authorization 头**，
                        // 所以握手阶段只能放行。鉴权改由 PageWebSocketHandler 在「首帧」完成，
                        // 它同样会查库校验用户是否仍在、令牌版本是否被改密码作废，
                        // 口径与 JwtAuthenticationFilter 一致，不会形成绕过。
                        .requestMatchers("/ws/**").permitAll()
                        // 错误转发路径
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeJson(response, HttpStatus.UNAUTHORIZED, 401, "未登录或登录已过期"))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeJson(response, HttpStatus.FORBIDDEN, 403, "无权访问")))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /** 开发期允许 Vite 前端（localhost:5173）直连；生产需改为实际域名。 */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:5173"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    private void writeJson(HttpServletResponse response, HttpStatus status, int code, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(code, message)));
    }
}
