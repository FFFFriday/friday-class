package com.fridayclass.security;

import com.fridayclass.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 签发与解析（HS256）。
 *
 * <p>密钥不硬编码：从配置 {@code jwt.secret} 注入（本地在 application-local.yml，
 * 生产用环境变量 JWT_SECRET）。启动时校验密钥长度，避免用弱密钥或空密钥运行。
 */
@Service
public class JwtService {

    /** HS256 要求密钥至少 256 位（32 字节）。 */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final long expireMs;

    public JwtService(@Value("${jwt.secret:}") String secret,
                      @Value("${jwt.expire-ms:604800000}") long expireMs) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT 密钥未配置。请设置环境变量 JWT_SECRET，或在 application-local.yml 中配置 jwt.secret。");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT 密钥过短：至少需要 " + MIN_SECRET_BYTES + " 字节，当前 " + bytes.length + " 字节。");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expireMs = expireMs;
    }

    /** 签发令牌。 */
    public String generateToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("uid", user.getId())
                .claim("role", user.getRole().name())
                .claim("ver", user.getTokenVersion())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expireMs)))
                .signWith(key)
                .compact();
    }

    /** 解析并校验令牌，返回载荷；签名错误/过期会抛 JwtException。 */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractUsername(String token) {
        return parse(token).getSubject();
    }
}
