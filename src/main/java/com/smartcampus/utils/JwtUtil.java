package com.smartcampus.utils;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration:86400000}")
    private long expiration;  // Access Token 有效期（默认24小时）

    @Value("${jwt.refresh-expiration:604800000}")  // 7天
    private long refreshExpiration;

    @Autowired(required = false)
    private RedisTemplate<String, String> redisTemplate;

    private javax.crypto.SecretKey key;

    @PostConstruct
    public void init() {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 Access Token（短有效期）
     */
    public String generateToken(Long userId, String username, String role) {
        Long version = getTokenVersion(userId);

        Map<String, Object> claims = new HashMap<>();
        claims.put("username", username);
        claims.put("role", role);
        claims.put("version", version);
        claims.put("type", "access");  // 标记为 access token

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(userId.toString())
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 生成 Refresh Token（长有效期）
     */
    public String generateRefreshToken(Long userId, String username, String role) {
        Long version = getTokenVersion(userId);

        Map<String, Object> claims = new HashMap<>();
        claims.put("username", username);
        claims.put("role", role);
        claims.put("version", version);
        claims.put("type", "refresh");  // 标记为 refresh token

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(userId.toString())
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + refreshExpiration))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 解析 token
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 从 token 获取 userId
     */
    public Long getUserIdFromToken(String token) {
        Claims claims = parseToken(token);
        return Long.parseLong(claims.getSubject());
    }

    /**
     * 验证 Access Token（检查版本号）
     */
    public boolean validateToken(String token) {
        try {
            Claims claims = parseToken(token);
            Long userId = Long.parseLong(claims.getSubject());
            Long tokenVersion = claims.get("version", Long.class);
            Long currentVersion = getTokenVersion(userId);

            if (tokenVersion == null || !tokenVersion.equals(currentVersion)) {
                System.out.println("❌ Token版本号不匹配，token已失效");
                return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 验证 Refresh Token（检查类型、版本号、有效期）
     */
    public boolean validateRefreshToken(String token) {
        try {
            Claims claims = parseToken(token);

            // 检查 token 类型
            String type = claims.get("type", String.class);
            if (!"refresh".equals(type)) {
                System.out.println("❌ 不是 Refresh Token");
                return false;
            }

            // 检查版本号（密码重置时会使所有 token 失效）
            Long userId = Long.parseLong(claims.getSubject());
            Long tokenVersion = claims.get("version", Long.class);
            Long currentVersion = getTokenVersion(userId);

            if (tokenVersion == null || !tokenVersion.equals(currentVersion)) {
                System.out.println("❌ Refresh Token 版本号不匹配");
                return false;
            }

            // 检查是否过期（JWT 自带过期检查）
            // parseToken 已经会检查过期时间，过期会抛异常

            return true;
        } catch (ExpiredJwtException e) {
            System.out.println("❌ Refresh Token 已过期");
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取用户的 token 版本号
     */
    private Long getTokenVersion(Long userId) {
        if (redisTemplate == null) {
            return 0L;
        }
        String key = "user:token:version:" + userId;
        String version = redisTemplate.opsForValue().get(key);
        return version != null ? Long.parseLong(version) : 0L;
    }

    /**
     * 获取 token 中的类型
     */
    public String getTokenType(String token) {
        try {
            Claims claims = parseToken(token);
            return claims.get("type", String.class);
        } catch (Exception e) {
            return null;
        }
    }
}