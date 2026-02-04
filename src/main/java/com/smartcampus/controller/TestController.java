package com.smartcampus.controller;

import com.smartcampus.entity.User;
import com.smartcampus.repository.UserRepository;
import com.smartcampus.service.EmailService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Timer;
import java.util.TimerTask;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:5173",
        allowedHeaders = "*",
        methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS},
        allowCredentials = "true")
public class TestController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailService emailService;  // 邮件服务

    @Autowired(required = false)  // 设置为非必须，避免启动失败
    private JavaMailSender javaMailSender;

    @Autowired
    private HttpServletRequest httpServletRequest;  // 用于获取客户端IP

    // ==================== 安全的随机数生成器 ====================
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DIGITS = "0123456789";

    // ==================== 频率限制存储 ====================
    // 存储验证码（key: "email:xxx@xx.com", value: "123456"）
    private final ConcurrentHashMap<String, String> emailCodes = new ConcurrentHashMap<>();

    // 注册频率限制（key: IP地址, value: 最后注册时间）
    private final ConcurrentHashMap<String, LocalDateTime> lastRegisterTime = new ConcurrentHashMap<>();

    // 验证码发送频率限制（key: "email:xxx@xx.com", value: 最后发送时间）
    private final ConcurrentHashMap<String, LocalDateTime> lastVerifyCodeTime = new ConcurrentHashMap<>();

    // ==================== 邮件测试接口 ====================
    @GetMapping("/mail/test")
    public ResponseEntity<?> testMail(@RequestParam String email) {
        try {
            if (email == null || !email.contains("@")) {
                return errorResponse(400, "邮箱格式不正确");
            }

            System.out.println("🧪 [邮件测试] 目标邮箱: " + email);

            String testCode = "123456";

            try {
                emailService.sendVerificationCode(email, testCode);
                System.out.println("✅ 测试邮件发送成功: " + email);
            } catch (Exception e) {
                System.err.println("❌ 邮件发送失败: " + e.getMessage());
                throw e;
            }

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "测试邮件发送成功");
            response.put("data", Map.of(
                    "email", email,
                    "test_code", testCode,
                    "timestamp", LocalDateTime.now().toString()
            ));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            return errorResponse(500, "测试邮件发送失败: " + e.getMessage());
        }
    }

    // ==================== 发送验证码接口（添加频率限制） ====================
    @PostMapping("/verify/email")
    public ResponseEntity<?> sendVerifyCode(@RequestBody Map<String, String> request) {
        try {
            String email = request.get("email");

            if (email == null || !email.contains("@")) {
                return errorResponse(400, "邮箱格式不正确");
            }

            System.out.println("📧 [发送验证码] 邮箱: " + email);

            // 1. 检查验证码发送频率（同一邮箱60秒内只能发送一次）
            String emailKey = "verify:" + email;
            LocalDateTime lastVerifyTime = lastVerifyCodeTime.get(emailKey);
            if (lastVerifyTime != null && Duration.between(lastVerifyTime, LocalDateTime.now()).getSeconds() < 60) {
                long remainingSeconds = 60 - Duration.between(lastVerifyTime, LocalDateTime.now()).getSeconds();
                return errorResponse(429, "验证码发送过于频繁，请" + remainingSeconds + "秒后再试");
            }

            // 2. 检查邮箱是否已被注册
            if (userRepository.existsByEmail(email)) {
                return errorResponse(400, "邮箱已被注册");
            }

            // 3. 生成6位随机验证码（使用安全的随机数生成器）
            String code = generateRandomCode();

            // 4. 存储验证码
            String key = "email:" + email;
            emailCodes.put(key, code);

            // 5. 设置10分钟后过期
            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    emailCodes.remove(key);
                    System.out.println("⏰ 验证码已过期: " + email);
                }
            }, 10 * 60 * 1000);

            // 6. 发送邮件
            boolean emailSent = false;
            try {
                // 检查EmailService是否可用
                if (emailService != null) {
                    emailService.sendVerificationCode(email, code);
                    emailSent = true;
                    System.out.println("✅ 验证码邮件发送成功: " + code);
                } else {
                    System.err.println("⚠️  EmailService未配置，使用模拟验证码");
                }
            } catch (Exception e) {
                System.err.println("❌ 邮件发送失败，使用模拟验证码: " + e.getMessage());
            }

            // 如果邮件发送失败，使用固定验证码（降级方案）
            if (!emailSent) {
                code = "123456";
                emailCodes.put(key, code);
                System.out.println("📱 开发备用验证码: " + code);
            }

            // 7. 记录发送时间
            lastVerifyCodeTime.put(emailKey, LocalDateTime.now());

            // 8. 返回成功响应
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "验证码发送成功");

            Map<String, Object> data = new HashMap<>();
            data.put("email", email);
            data.put("expiresIn", 600);  // 10分钟

            // 如果是开发模式，添加提示
            if (!emailSent) {
                data.put("tip", "开发模式：验证码固定为 123456");
            }

            response.put("data", data);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            return errorResponse(500, "发送验证码失败: " + e.getMessage());
        }
    }

    // ==================== 注册接口（添加完整安全限制） ====================
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, Object> request) {
        try {
            String username = getStringValue(request, "username");
            String password = getStringValue(request, "password");
            String email = getStringValue(request, "email");
            String verifyCode = getStringValue(request, "verifyCode");
            String studentId = getStringValue(request, "studentId");
            String major = getStringValue(request, "major");
            String college = getStringValue(request, "college");
            String grade = getStringValue(request, "grade");
            Integer gender = getIntegerValue(request, "gender", 0);

            System.out.println("📝 [注册] 收到数据：" + request);

            // ========== 新增安全限制 ==========

            // 1. 获取客户端IP地址
            String clientIp = getClientIp();
            System.out.println("🌐 客户端IP: " + clientIp);

            // 2. 检查注册频率（同一IP30秒内只能注册一次）
            LocalDateTime lastTime = lastRegisterTime.get(clientIp);
            if (lastTime != null && Duration.between(lastTime, LocalDateTime.now()).getSeconds() < 30) {
                long remainingSeconds = 30 - Duration.between(lastTime, LocalDateTime.now()).getSeconds();
                return errorResponse(429, "注册过于频繁，请" + remainingSeconds + "秒后再试");
            }

            // 3. 验证用户名规则
            if (!isValidUsername(username)) {
                return errorResponse(400, "用户名只能包含字母、数字和下划线，长度3-20位");
            }

            // 4. 验证密码强度
            if (!isValidPassword(password)) {
                return errorResponse(400, "密码至少6位，需包含字母和数字");
            }

            // ========== 原有验证逻辑 ==========

            // 5. 验证必填字段
            if (username == null || username.trim().isEmpty()) {
                return errorResponse(400, "用户名不能为空");
            }
            if (password == null || password.length() < 6) {
                return errorResponse(400, "密码长度至少6位");
            }
            if (email == null || !email.contains("@")) {
                return errorResponse(400, "邮箱格式不正确");
            }

            // 6. 验证验证码
            if (!verifyEmailCode(email, verifyCode)) {
                return errorResponse(400, "验证码错误或已过期");
            }

            // 7. 检查用户名是否已存在
            if (userRepository.existsByUsername(username)) {
                return errorResponse(400, "用户名已存在");
            }

            // 8. 检查邮箱是否已存在
            if (userRepository.existsByEmail(email)) {
                return errorResponse(400, "邮箱已注册");
            }

            // 9. 检查学号是否已存在
            if (studentId != null && !studentId.trim().isEmpty()) {
                if (userRepository.existsByStudentId(studentId)) {
                    return errorResponse(400, "学号已注册");
                }
            }

            // 10. 创建新用户
            User user = new User();
            user.setUsername(username.trim());
            user.setPassword(encodePassword(password));
            user.setEmail(email.trim());
            user.setGender(gender);
            user.setStatus(1);
            user.setRole("user");

            if (studentId != null) user.setStudentId(studentId.trim());
            if (major != null) user.setMajor(major.trim());
            if (college != null) user.setCollege(college.trim());
            if (grade != null) user.setGrade(grade.trim());

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("theme", "light");
            metadata.put("notifications", true);
            metadata.put("registered_via", "web");
            metadata.put("registration_time", LocalDateTime.now().toString());
            metadata.put("register_ip", clientIp);  // 记录注册IP
            user.setMetadata(metadata);

            userRepository.save(user);

            // 11. 更新最后注册时间
            lastRegisterTime.put(clientIp, LocalDateTime.now());

            // 12. 返回成功
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "注册成功");
            response.put("data", null);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            return errorResponse(500, "注册失败：" + e.getMessage());
        }
    }

    // ==================== 生成6位安全随机验证码 ====================
    private String generateRandomCode() {
        StringBuilder code = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            code.append(DIGITS.charAt(RANDOM.nextInt(DIGITS.length())));
        }
        String generatedCode = code.toString();
        System.out.println("🔐 生成随机验证码: " + generatedCode);
        return generatedCode;
    }

    // ==================== 安全验证辅助方法 ====================

    // 验证邮箱验证码
    private boolean verifyEmailCode(String email, String code) {
        if (email == null || code == null) {
            return false;
        }

        String key = "email:" + email;
        String storedCode = emailCodes.get(key);

        if (storedCode == null) {
            System.out.println("❌ 验证码不存在或已过期");
            return false;
        }

        boolean isValid = storedCode.equals(code);

        if (isValid) {
            // 验证成功后移除
            emailCodes.remove(key);
            System.out.println("✅ 邮箱验证成功: " + email);
        } else {
            System.out.println("❌ 验证码错误，期望: " + storedCode + "，收到: " + code);
        }

        return isValid;
    }

    // 获取客户端IP地址
    private String getClientIp() {
        try {
            String ip = httpServletRequest.getHeader("X-Forwarded-For");
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = httpServletRequest.getHeader("Proxy-Client-IP");
            }
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = httpServletRequest.getHeader("WL-Proxy-Client-IP");
            }
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = httpServletRequest.getRemoteAddr();
            }
            // 处理多个IP的情况（如代理链）
            if (ip != null && ip.contains(",")) {
                ip = ip.split(",")[0].trim();
            }
            return ip;
        } catch (Exception e) {
            return "unknown";
        }
    }

    // 验证用户名格式
    private boolean isValidUsername(String username) {
        if (username == null || username.length() < 3 || username.length() > 20) {
            return false;
        }
        // 只允许字母、数字、下划线
        return username.matches("^[a-zA-Z0-9_]+$");
    }

    // 验证密码强度
    private boolean isValidPassword(String password) {
        if (password == null || password.length() < 6) {
            return false;
        }
        // 至少包含一个字母和一个数字
        boolean hasLetter = password.matches(".*[a-zA-Z].*");
        boolean hasDigit = password.matches(".*\\d.*");
        return hasLetter && hasDigit;
    }

    // ==================== 其他接口保持不变 ====================

    // 简单密码加密
    private String encodePassword(String rawPassword) {
        return "encrypted_" + rawPassword + "_" + System.currentTimeMillis();
    }

    private boolean checkPassword(String rawPassword, String encodedPassword) {
        return encodedPassword != null && encodedPassword.startsWith("encrypted_");
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> request) {
        try {
            String username = request.get("username");
            String password = request.get("password");
            String captcha = request.get("captcha");
            String captchaId = request.get("captchaId");

            System.out.println("🔑 [登录] 收到数据：" + request);

            // 1. 验证验证码
            if (!"123456".equals(captcha)) {
                return errorResponse(400, "验证码错误");
            }

            // 2. 查找用户
            User user = userRepository.findByUsername(username)
                    .orElse(userRepository.findByEmail(username).orElse(null));

            if (user == null) {
                return errorResponse(400, "用户不存在");
            }

            if (user.getStatus() == 0) {
                return errorResponse(403, "账号已被禁用");
            }

            // 3. 验证密码
            if (!checkPassword(password, user.getPassword())) {
                return errorResponse(400, "密码错误");
            }

            // 4. 更新最后登录时间
            user.setLastLoginAt(LocalDateTime.now());
            userRepository.save(user);

            // 5. 生成模拟token
            String token = "jwt-" + user.getId() + "-" + System.currentTimeMillis();

            // 6. 构建返回数据
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "登录成功");

            Map<String, Object> data = new HashMap<>();
            data.put("token", token);
            data.put("refreshToken", token + "-refresh");

            Map<String, Object> userInfo = new HashMap<>();
            userInfo.put("id", user.getId());
            userInfo.put("username", user.getUsername());
            userInfo.put("email", user.getEmail());
            userInfo.put("gender", user.getGender());
            userInfo.put("genderText", user.getGenderText());
            userInfo.put("avatarUrl", user.getAvatarUrl());
            userInfo.put("avatar", user.getAvatar());
            userInfo.put("status", user.getStatus());
            userInfo.put("statusText", user.getStatusText());
            userInfo.put("role", user.getRole());
            userInfo.put("studentId", user.getStudentId());
            userInfo.put("major", user.getMajor());
            userInfo.put("college", user.getCollege());
            userInfo.put("grade", user.getGrade());
            userInfo.put("createdAt", user.getCreatedAt());
            userInfo.put("lastLoginAt", user.getLastLoginAt());
            userInfo.put("metadata", user.getMetadata());

            data.put("user", userInfo);
            response.put("data", data);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            return errorResponse(500, "登录失败：" + e.getMessage());
        }
    }

    @GetMapping("/user/profile")
    public ResponseEntity<?> getUserProfile(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return errorResponse(401, "未授权，请先登录");
        }

        try {
            String token = authHeader.substring(7);
            if (!token.startsWith("jwt-")) {
                return errorResponse(401, "Token格式错误");
            }

            String[] parts = token.split("-");
            if (parts.length < 2) {
                return errorResponse(401, "Token格式错误");
            }

            Integer userId = Integer.parseInt(parts[1]);
            Optional<User> userOptional = userRepository.findById(userId);

            if (userOptional.isEmpty()) {
                return errorResponse(404, "用户不存在");
            }

            User user = userOptional.get();

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "获取成功");
            response.put("data", user);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            return errorResponse(500, "获取用户信息失败");
        }
    }

    @GetMapping("/test")
    public ResponseEntity<?> test() {
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "✅ 后端运行正常，PostgreSQL 连接成功！");
        response.put("timestamp", LocalDateTime.now());

        boolean mailServiceAvailable = emailService != null;

        Map<String, Object> dbInfo = new HashMap<>();
        dbInfo.put("database", "smart_campus");
        dbInfo.put("users_count", userRepository.count());
        dbInfo.put("mail_service", mailServiceAvailable ? "可用" : "不可用");
        response.put("data", dbInfo);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/users")
    public ResponseEntity<?> getAllUsers() {
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "获取用户列表成功");
        response.put("data", userRepository.findAll());
        return ResponseEntity.ok(response);
    }

    // ==================== 基础辅助方法 ====================
    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private Integer getIntegerValue(Map<String, Object> map, String key, Integer defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Integer) return (Integer) value;
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private ResponseEntity<?> errorResponse(int code, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", code);
        response.put("message", message);
        response.put("data", null);
        return ResponseEntity.status(code).body(response);
    }
}