package com.fridayclass.security;

import com.fridayclass.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器：从 Authorization: Bearer &lt;token&gt; 解析令牌，
 * 校验通过后查库确认用户仍存在（软删除的用户会被 @SQLRestriction 过滤掉），
 * 再写入 SecurityContext。
 *
 * <p>令牌无效时不抛异常，只清空上下文并放行，由后续的认证入口点统一返回 401。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        try {
            Claims claims = jwtService.parse(token);
            String username = claims.getSubject();
            Integer tokenVersion = claims.get("ver", Integer.class);

            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                // 软删除的账号，其令牌即便未过期也应失效
                userRepository.findByUsernameAndDeletedFalse(username)
                        // 令牌版本必须与库里一致：改密码后版本自增，旧令牌随即失效
                        .filter(user -> tokenVersion != null
                                && tokenVersion.equals(user.getTokenVersion()))
                        .ifPresent(user -> {
                    UserPrincipal principal = new UserPrincipal(user);
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    principal, null, principal.getAuthorities());
                    authentication.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                });
            }
        } catch (JwtException | IllegalArgumentException ex) {
            // 令牌非法/过期：不认为是致命错误，交由入口点返回 401
            log.debug("jwt_invalid reason={}", ex.getMessage());
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }
}
