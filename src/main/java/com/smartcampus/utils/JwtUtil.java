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

    @Value("${jwt.secret:smart-campus-secret-key-change-in-production}")
    private String secret;

    @Value("${jwt.expiration:86400000}")
    private long expiration;

    @Autowired(required = false)
    private RedisTemplate<String, String> redisTemplate;  // ✅ 注入 Redis

    private javax.crypto.SecretKey key;

    @PostConstruct
    public void init() {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    // ✅ 修改：生成token - 加入版本号
    public String generateToken(Long userId, String username, String role) {
        // 获取当前 token 版本号
        Long version = getTokenVersion(userId);

        Map<String, Object> claims = new HashMap<>();
        claims.put("username", username);
        claims.put("role", role);
        claims.put("version", version);  // ✅ 加入版本号

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(userId.toString())
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    // 解析token
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long getUserIdFromToken(String token) {
        Claims claims = parseToken(token);
        return Long.parseLong(claims.getSubject());
    }

    // ✅ 修改：验证token（加入版本号校验）
    public boolean validateToken(String token) {
        try {
            Claims claims = parseToken(token);
            Long userId = Long.parseLong(claims.getSubject());
            Long tokenVersion = claims.get("version", Long.class);
            Long currentVersion = getTokenVersion(userId);

            // 版本号不匹配 → token 已失效
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
     * 获取用户的 token 版本号
     */
    private Long getTokenVersion(Long userId) {
        if (redisTemplate == null) {
            return 0L;  // Redis 不可用时返回 0
        }
        String key = "user:token:version:" + userId;
        String version = redisTemplate.opsForValue().get(key);
        return version != null ? Long.parseLong(version) : 0L;
    }
}