package com.smartcampus.controller;

import com.smartcampus.dto.ApiResponse;
import com.smartcampus.entity.PasswordResetLog;
import com.smartcampus.entity.User;
import com.smartcampus.repository.PasswordResetLogRepository;
import com.smartcampus.repository.UserRepository;
import com.smartcampus.service.EmailService;
import com.smartcampus.utils.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = {"http://localhost:5173",
        "http://8.134.179.88",
        "http://localhost"},
        allowedHeaders = "*",
        methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS},
        allowCredentials = "true")
public class TestController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired(required = false)
    private JavaMailSender javaMailSender;

    @Autowired
    private HttpServletRequest httpServletRequest;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;  // 新增：注入 Redis

    @Autowired
    private PasswordResetLogRepository passwordResetLogRepository;

    private static final Logger logger = LoggerFactory.getLogger(TestController.class);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DIGITS = "0123456789";
    private static final String CAPTCHA_DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    // 频率限制存储（保留用于 IP 注册限制）
    private final ConcurrentHashMap<String, LocalDateTime> lastRegisterTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LocalDateTime> lastVerifyCodeTime = new ConcurrentHashMap<>();

    // ==================== 图形验证码接口（无状态 Redis 版） ====================
    @GetMapping("/captcha")
    @ResponseBody
    public Map<String, Object> generateCaptcha() {
        try {
            // 生成4位随机字符
            StringBuilder captcha = new StringBuilder(4);
            for (int i = 0; i < 4; i++) {
                captcha.append(CAPTCHA_DIGITS.charAt(RANDOM.nextInt(CAPTCHA_DIGITS.length())));
            }
            String captchaText = captcha.toString();

            // 生成唯一 ID
            String captchaId = UUID.randomUUID().toString();

            // 存入 Redis，有效期 5 分钟
            String redisKey = "captcha:" + captchaId;
            redisTemplate.opsForValue().set(redisKey, captchaText, Duration.ofMinutes(5));

            System.out.println("🔐 [生成图形验证码] captchaId: " + captchaId + ", 验证码: " + captchaText);

            // 生成验证码图片
            String captchaBase64 = generateCaptchaImage(captchaText);

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("data", captchaText);
            result.put("captchaId", captchaId);
            result.put("captchaBase64", captchaBase64);
            result.put("message", "验证码生成成功");
            result.put("expiresIn", 300);

            return result;

        } catch (Exception e) {
            logger.error("验证码生成失败: {}", e.getMessage(), e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("code", 500);
            errorResult.put("message", "验证码生成失败: " + e.getMessage());
            errorResult.put("data", null);
            return errorResult;
        }
    }

    // 验证图形验证码（无状态 Redis 版）
    private boolean validateCaptcha(String captchaId, String userCaptcha) {
        if (captchaId == null || userCaptcha == null || userCaptcha.trim().isEmpty()) {
            System.out.println("❌ 验证码验证失败：captchaId 或用户验证码为空");
            return false;
        }

        String redisKey = "captcha:" + captchaId;
        String savedCaptcha = redisTemplate.opsForValue().get(redisKey);

        System.out.println("🔍 [验证图形验证码] captchaId: " + captchaId + ", 用户输入: " + userCaptcha + ", 保存的: " + savedCaptcha);

        if (savedCaptcha == null) {
            System.out.println("❌ 验证码验证失败：验证码已过期或不存在");
            return false;
        }

        boolean isValid = savedCaptcha.equalsIgnoreCase(userCaptcha.trim());

        if (isValid) {
            // 验证成功后删除，防止重复使用
            redisTemplate.delete(redisKey);
            System.out.println("✅ 图形验证码验证成功");
        } else {
            System.out.println("❌ 验证码验证失败：不匹配");
        }

        return isValid;
    }

    private String generateCaptchaImage(String text) {
        try {
            int width = 120;
            int height = 40;
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = image.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            GradientPaint gradient = new GradientPaint(0, 0, new Color(240, 240, 240),
                    width, height, new Color(200, 200, 200));
            g2d.setPaint(gradient);
            g2d.fillRect(0, 0, width, height);
            g2d.setColor(new Color(180, 180, 180));
            for (int i = 0; i < 100; i++) {
                int x = RANDOM.nextInt(width);
                int y = RANDOM.nextInt(height);
                g2d.fillRect(x, y, 2, 2);
            }
            Font[] fonts = {
                    new Font("Arial", Font.BOLD, 28),
                    new Font("Courier New", Font.BOLD, 28),
                    new Font("Times New Roman", Font.BOLD, 28)
            };
            int charWidth = width / (text.length() + 1);
            for (int i = 0; i < text.length(); i++) {
                Font font = fonts[RANDOM.nextInt(fonts.length)];
                g2d.setFont(font);
                Color color = new Color(
                        RANDOM.nextInt(100) + 50,
                        RANDOM.nextInt(100) + 50,
                        RANDOM.nextInt(100) + 50
                );
                g2d.setColor(color);
                double angle = (RANDOM.nextDouble() - 0.5) * Math.PI / 6;
                g2d.rotate(angle, charWidth * (i + 1), (double) height / 2);
                String ch = String.valueOf(text.charAt(i));
                g2d.drawString(ch, charWidth * (i + 1) - 10, height / 2 + 10);
                g2d.rotate(-angle, charWidth * (i + 1), (double) height / 2);
            }
            g2d.setColor(new Color(150, 150, 150, 100));
            for (int i = 0; i < 5; i++) {
                int x1 = RANDOM.nextInt(width / 2);
                int y1 = RANDOM.nextInt(height);
                int x2 = width / 2 + RANDOM.nextInt(width / 2);
                int y2 = RANDOM.nextInt(height);
                g2d.setStroke(new BasicStroke(1.5f));
                g2d.drawLine(x1, y1, x2, y2);
            }
            g2d.setColor(Color.LIGHT_GRAY);
            g2d.setStroke(new BasicStroke(2f));
            g2d.drawRect(1, 1, width - 3, height - 3);
            g2d.dispose();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
            return "data:image/png;base64," + base64;
        } catch (Exception e) {
            System.err.println("❌ 生成验证码图片失败: " + e.getMessage());
            return "";
        }
    }

    // ==================== ✅ 新增：发送重置密码验证码接口 ====================
    @PostMapping("/password/reset/send")
    public ResponseEntity<?> sendResetCode(@RequestBody Map<String, String> request) {
        try {
            String email = request.get("email");
            String captcha = request.get("captcha");
            String captchaId = request.get("captchaId");

            System.out.println("📧 [发送重置验证码] 邮箱: " + email);

            // 验证邮箱格式
            if (!isValidEmail(email)) {
                return errorResponse(400, "邮箱格式不正确");
            }

            // 验证图形验证码
            if (!validateCaptcha(captchaId, captcha)) {
                return errorResponse(400, "验证码错误或已过期");
            }

            // 检查邮箱是否存在
            Optional<User> userOpt = userRepository.findByEmail(email);
            if (userOpt.isEmpty()) {
                return errorResponse(404, "该邮箱未注册");
            }

            // 检查发送频率（同一邮箱60秒内只能发送一次）
            String emailKey = "reset:limit:" + email;
            LocalDateTime lastTime = lastVerifyCodeTime.get(emailKey);
            if (lastTime != null && Duration.between(lastTime, LocalDateTime.now()).getSeconds() < 60) {
                long remainingSeconds = 60 - Duration.between(lastTime, LocalDateTime.now()).getSeconds();
                return errorResponse(429, "验证码发送过于频繁，请" + remainingSeconds + "秒后再试");
            }

            // 生成6位随机验证码
            String code = generateRandomCode();

            // 存储验证码到 Redis（有效期10分钟）
            String redisKey = "reset:email:" + email;
            redisTemplate.opsForValue().set(redisKey, code, Duration.ofMinutes(10));

            // 发送邮件
            try {
                emailService.sendVerificationCode(email, code);
                System.out.println("✅ 重置验证码邮件发送成功: " + email + ", 验证码: " + code);
            } catch (Exception e) {
                System.err.println("❌ 邮件发送失败: " + e.getMessage());
                return errorResponse(500, "邮件发送失败，请稍后重试");
            }

            // 记录发送时间
            lastVerifyCodeTime.put(emailKey, LocalDateTime.now());

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "验证码已发送至邮箱");
            response.put("data", null);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("发送重置验证码失败: {}", e.getMessage(), e);
            return errorResponse(500, "发送失败：" + e.getMessage());
        }
    }

    // ==================== ✅ 新增：重置密码接口 ====================
    @PostMapping("/password/reset")
    public ResponseEntity<?> resetPassword(@RequestBody Map<String, String> request, HttpServletRequest httpRequest) {
        try {
            String email = request.get("email");
            String verifyCode = request.get("verifyCode");
            String newPassword = request.get("newPassword");

            System.out.println("🔑 [重置密码] 邮箱: " + email);

            // 1. 验证必填字段
            if (!isValidEmail(email)) {
                return errorResponse(400, "邮箱格式不正确");
            }
            if (verifyCode == null || verifyCode.trim().isEmpty()) {
                return errorResponse(400, "请输入验证码");
            }
            if (newPassword == null || newPassword.trim().isEmpty()) {
                return errorResponse(400, "请输入新密码");
            }

            // 2. 验证密码强度
            if (!isValidPassword(newPassword)) {
                return errorResponse(400, "密码必须为8-20位，且包含大写字母、小写字母和数字");
            }

            // 3. 验证邮箱验证码
            String redisKey = "reset:email:" + email;
            String savedCode = redisTemplate.opsForValue().get(redisKey);

            if (savedCode == null) {
                // 记录失败日志
                saveResetLog(null, email, false, "验证码已过期或不存在", httpRequest);
                return errorResponse(400, "验证码已过期或不存在");
            }
            if (!savedCode.equals(verifyCode)) {
                saveResetLog(null, email, false, "验证码错误", httpRequest);
                return errorResponse(400, "验证码错误");
            }

            // 4. 验证成功后删除验证码
            redisTemplate.delete(redisKey);

            // 5. 查找用户
            User user = userRepository.findByEmail(email)
                    .orElse(null);

            if (user == null) {
                saveResetLog(null, email, false, "用户不存在", httpRequest);
                return errorResponse(404, "用户不存在");
            }

            // 6. 更新密码
            user.setPassword(passwordEncoder.encode(newPassword));
            userRepository.save(user);

            // 7. 清除该用户的所有 token（更新 Redis 中的 token 版本号）
            String tokenVersionKey = "user:token:version:" + user.getId();
            redisTemplate.opsForValue().increment(tokenVersionKey);
            redisTemplate.expire(tokenVersionKey, Duration.ofDays(7));
            System.out.println("✅ 用户 " + user.getUsername() + " 的 token 版本已更新");

            // 8. 记录成功日志
            saveResetLog(user.getId(), email, true, null, httpRequest);

            // 9. 返回成功
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "密码重置成功，请重新登录");
            response.put("data", null);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("重置密码失败: {}", e.getMessage(), e);
            return errorResponse(500, "重置失败：" + e.getMessage());
        }
    }

    /**
     * 保存重置日志
     */
    private void saveResetLog(Integer userId, String email, boolean success, String failReason, HttpServletRequest request) {
        try {
            PasswordResetLog log = new PasswordResetLog();
            log.setUserId(userId != null ? userId : 0);
            log.setEmail(email);
            log.setResetTime(LocalDateTime.now());
            log.setResetIp(getClientIp());
            log.setUserAgent(request.getHeader("User-Agent"));
            log.setSuccess(success);
            if (failReason != null) {
                log.setFailReason(failReason);
            }
            passwordResetLogRepository.save(log);
            System.out.println("📝 [重置日志] 已记录: " + email + ", 成功: " + success);
        } catch (Exception e) {
            System.err.println("❌ 记录重置日志失败: " + e.getMessage());
        }
    }

    // ==================== 邮件测试接口 ====================
    @GetMapping("/mail/test")
    public ResponseEntity<?> testMail(@RequestParam String email) {
        try {
            if (!isValidEmail(email)) {
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
            logger.error("操作失败，原因: {}", e.getMessage(), e);
            return errorResponse(500, "测试邮件发送失败: " + e.getMessage());
        }
    }

    // ==================== 发送验证码接口（添加频率限制） ====================
    @PostMapping("/verify/email")
    public ResponseEntity<?> sendVerifyCode(@RequestBody Map<String, String> request, HttpSession session) {
        try {
            String email = request.get("email");

            System.out.println("📧 [发送邮箱验证码] 邮箱: " + email);

            if (!isValidEmail(email)) {
                return errorResponse(400, "邮箱格式不正确");
            }

            // 1. 检查验证码发送频率（同一邮箱60秒内只能发送一次）
            String emailKey = "verify:limit:" + email;
            LocalDateTime lastTime = lastVerifyCodeTime.get(emailKey);
            if (lastTime != null && Duration.between(lastTime, LocalDateTime.now()).getSeconds() < 60) {
                long remainingSeconds = 60 - Duration.between(lastTime, LocalDateTime.now()).getSeconds();
                return errorResponse(429, "验证码发送过于频繁，请" + remainingSeconds + "秒后再试");
            }

            // 2. 检查邮箱是否已被注册
            if (userRepository.existsByEmail(email)) {
                return errorResponse(400, "邮箱已被注册");
            }

            // 3. 生成6位随机验证码
            String code = generateRandomCode();

            // 4. 存储验证码到 Redis（有效期10分钟）
            String redisKey = "register:email:" + email;
            redisTemplate.opsForValue().set(redisKey, code, Duration.ofMinutes(10));

            // 5. 发送邮件
            try {
                emailService.sendVerificationCode(email, code);
                System.out.println("✅ 注册验证码邮件发送成功: " + email + ", 验证码: " + code);
            } catch (Exception e) {
                System.err.println("❌ 邮件发送失败: " + e.getMessage());
                return errorResponse(500, "邮件发送失败，请稍后重试");
            }

            // 6. 记录发送时间
            lastVerifyCodeTime.put(emailKey, LocalDateTime.now());

            // 7. 返回成功
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "验证码已发送");
            response.put("data", null);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("发送验证码失败: {}", e.getMessage(), e);
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
            Integer gender = getIntegerValue(request);

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
                return errorResponse(400, "密码必须为8-20位，且包含大写字母、小写字母和数字");
            }

            // ========== 原有验证逻辑 ==========

            // 5. 验证必填字段
            if (username.trim().isEmpty()) {
                return errorResponse(400, "用户名不能为空");
            }
            if (password.length() < 6) {
                return errorResponse(400, "密码长度至少6位");
            }
            if (!isValidEmail(email)) {
                return errorResponse(400, "邮箱格式不正确");
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

            String redisKey = "register:email:" + email;
            String savedCode = redisTemplate.opsForValue().get(redisKey);

            if (savedCode == null) {
                return errorResponse(400, "验证码已过期或不存在");
            }
            if (!savedCode.equals(verifyCode)) {
                return errorResponse(400, "验证码错误");
            }

            // 验证成功后删除验证码
            redisTemplate.delete(redisKey);

            // 检查用户名是否已存在
            if (userRepository.existsByUsername(username)) {
                return errorResponse(400, "用户名已存在");
            }

            // 检查邮箱是否已存在
            if (userRepository.existsByEmail(email)) {
                return errorResponse(400, "邮箱已注册");
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
            logger.error("操作失败，原因: {}", e.getMessage(), e);
            return errorResponse(500, "注册失败：" + e.getMessage());
        }
    }

    // ==================== 生成6位安全随机验证码 ====================
    private String generateRandomCode() {
        StringBuilder code = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            code.append(DIGITS.charAt(RANDOM.nextInt(DIGITS.length())));
        }
        return code.toString();
    }

    // ==================== 安全验证辅助方法 ====================

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

    /**
     * 验证密码强度
     * 规则：
     * - 长度8-20位
     * - 至少包含一个大写字母
     * - 至少包含一个小写字母
     * - 至少包含一个数字
     * - 可选：至少包含一个特殊字符（推荐）
     */
    private boolean isValidPassword(String password) {
        if (password == null || password.isEmpty()) {
            return false;
        }

        // 1. 长度限制 8-20
        if (password.length() < 8 || password.length() > 20) {
            return false;
        }

        // 2. 检查是否包含大写字母
        boolean hasUpperCase = password.matches(".*[A-Z].*");
        // 3. 检查是否包含小写字母
        boolean hasLowerCase = password.matches(".*[a-z].*");
        // 4. 检查是否包含数字
        boolean hasDigit = password.matches(".*\\d.*");

        if (!hasUpperCase || !hasLowerCase || !hasDigit) {
            return false;
        }

        // 5. 可选：检查是否包含特殊字符（不强制，但推荐）
        // boolean hasSpecial = password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].*");

        // 6. 不允许有空格
        return !password.contains(" ");
    }

    // ==================== 其他接口保持不变 ====================

    private String encodePassword(String rawPassword) {
        // 使用BCrypt强哈希加密（安全）
        return passwordEncoder.encode(rawPassword);
    }



    private boolean checkPassword(String rawPassword, String encodedPassword) {
        // 使用passwordEncoder验证密码
        return passwordEncoder.matches(rawPassword, encodedPassword);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> request) {
        try {
            String username = request.get("username");
            String password = request.get("password");
            String captcha = request.get("captcha");
            String captchaId = request.get("captchaId");

            System.out.println("🔑 [登录] 收到请求: username=" + username);

            // 1. 验证图形验证码（第一道防线）
            if (!validateCaptcha(captchaId, captcha)) {
                return errorResponse(400, "验证码错误或已过期");
            }

            // 2. 检查登录失败次数（防止暴力破解）
            String failKey = "login:fail:" + username;
            String failCountStr = redisTemplate.opsForValue().get(failKey);
            int failCount = failCountStr != null ? Integer.parseInt(failCountStr) : 0;

            // 失败超过5次，锁定15分钟
            if (failCount >= 5) {
                long ttl = redisTemplate.getExpire(failKey, java.util.concurrent.TimeUnit.SECONDS);
                if (ttl > 0) {
                    long minutes = ttl / 60;
                    long seconds = ttl % 60;
                    String waitTime = minutes > 0 ? minutes + "分钟" : seconds + "秒";
                    return errorResponse(429, "登录失败次数过多，请" + waitTime + "后重试");
                } else {
                    // TTL 过期，清理记录
                    redisTemplate.delete(failKey);
                    failCount = 0;
                }
            }

            // 3. 查找用户（支持用户名或邮箱）
            User user = userRepository.findByUsername(username)
                    .orElse(userRepository.findByEmail(username).orElse(null));

            // 4. 统一验证：用户名或密码错误（不区分具体原因）
            boolean isValid = user != null && passwordEncoder.matches(password, user.getPassword());

            if (!isValid) {
                // 记录失败次数
                failCount++;
                redisTemplate.opsForValue().set(failKey, String.valueOf(failCount), Duration.ofMinutes(15));

                // 记录日志（用于安全审计，不返回给前端）
                logger.warn("登录失败: username={}, reason={}, failCount={}",
                        username,
                        user == null ? "用户不存在" : "密码错误",
                        failCount);

                // 统一返回错误信息
                if (failCount >= 5) {
                    return errorResponse(429, "登录失败次数过多，请15分钟后重试");
                }
                return errorResponse(401, "用户名或密码错误");
            }

            // 5. 登录成功，清除失败记录
            redisTemplate.delete(failKey);

            // 6. 检查账号状态
            if (user.getStatus() == 0) {
                return errorResponse(403, "账号已被禁用，请联系管理员");
            }

            // 7. 更新最后登录时间
            user.setLastLoginAt(LocalDateTime.now());
            userRepository.save(user);

            // 8. 生成 token
            String token = jwtUtil.generateToken(Long.valueOf(user.getId()), user.getUsername(), user.getRole());
            String refreshToken = jwtUtil.generateRefreshToken(Long.valueOf(user.getId()), user.getUsername(), user.getRole());

            System.out.println("✅ [登录成功] username=" + username + ", userId=" + user.getId());

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "登录成功");

            Map<String, Object> data = new HashMap<>();
            data.put("token", token);
            data.put("refreshToken", refreshToken);
            data.put("role", user.getRole());
            data.put("username", user.getUsername());
            data.put("email", user.getEmail() != null ? user.getEmail() : "");
            data.put("avatar", user.getAvatarUrl() != null ? user.getAvatarUrl() : "/api/avatars/default-avatar.png");
            data.put("studentId", user.getStudentId() != null ? user.getStudentId() : "");
            data.put("major", user.getMajor() != null ? user.getMajor() : "");
            data.put("college", user.getCollege() != null ? user.getCollege() : "");
            data.put("grade", user.getGrade() != null ? user.getGrade() : "");
            data.put("gender", user.getGender());
            data.put("genderText", user.getGenderText());

            response.put("data", data);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("登录失败: {}", e.getMessage(), e);
            return errorResponse(500, "登录失败，请稍后重试");
        }
    }

    @GetMapping("/user/profile")
    public ResponseEntity<?> getUserProfile(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            Integer userId = extractUserIdFromToken(authHeader);

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

        } catch (IllegalArgumentException e) {
            // token 格式错误
            return errorResponse(401, e.getMessage());
        } catch (RuntimeException e) {
            // token 无效
            return errorResponse(401, "Token无效");
        } catch (Exception e) {
            logger.error("操作失败，原因: {}", e.getMessage(), e);
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

    // ==================== 新增：简单邮件测试接口 ====================
    @PostMapping("/test-email")
    @ResponseBody
    public Map<String, Object> testEmail(@RequestBody Map<String, String> request) {
        try {
            String toEmail = request.get("email");

            if (!isValidEmail(toEmail)) {
                return Map.of("code", 400, "message", "邮箱格式不正确");
            }

            String testCode = "TEST1234"; // 一个固定的测试验证码

            System.out.println("📧 [简单邮件测试] 目标邮箱: " + toEmail);

            try {
                // 直接调用邮件服务发送
                emailService.sendVerificationCode(toEmail, testCode);
                System.out.println("✅ 测试邮件发送成功: " + toEmail);

                Map<String, Object> response = new HashMap<>();
                response.put("code", 200);
                response.put("message", "测试邮件已发送至: " + toEmail);
                response.put("data", Map.of(
                        "email", toEmail,
                        "test_code", testCode,
                        "timestamp", LocalDateTime.now().toString()
                ));

                return response;

            } catch (Exception e) {
                logger.error("操作失败，原因: {}", e.getMessage(), e); // 这会把错误详情打印到后端日志里
                System.err.println("❌ 邮件发送失败: " + e.getMessage());

                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("code", 500);
                errorResponse.put("message", "邮件发送失败: " + e.getMessage());
                errorResponse.put("data", null);

                return errorResponse;
            }

        } catch (Exception e) {
            logger.error("操作失败，原因: {}", e.getMessage(), e);
            return Map.of("code", 500, "message", "处理请求时出错: " + e.getMessage());
        }
    }

    // ==================== 需要新增的接口 ====================
// 1. 退出登录接口
    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return errorResponse(1005, "Token无效，退出失败");
            }

            String token = authHeader.substring(7);
            // 实际应该将token加入黑名单或删除，这里简单返回成功
            System.out.println("🔓 [退出登录] Token: " + token);

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "退出登录成功");
            response.put("data", null);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("操作失败，原因: {}", e.getMessage(), e);
            return errorResponse(1005, "Token无效，退出失败");
        }
    }

    // ==================== 自动登录接口（不需要验证码） ====================
    @PostMapping("/login/credentials")
    public ResponseEntity<?> loginWithCredentials(@RequestBody Map<String, String> request) {
        try {
            String username = request.get("username");
            String password = request.get("password");

            System.out.println("🔑 [自动登录] 收到请求: username=" + username);

            // 1. 查找用户
            User user = userRepository.findByUsername(username)
                    .orElse(userRepository.findByEmail(username).orElse(null));

            // 2. 统一验证（不区分用户不存在还是密码错误）
            boolean isValid = user != null && passwordEncoder.matches(password, user.getPassword());

            if (!isValid) {
                // 只记录日志，不增加失败次数，不锁定
                logger.warn("自动登录失败: username={}, reason={}",
                        username,
                        user == null ? "用户不存在" : "密码错误");

                // 统一返回错误信息
                return errorResponse(401, "用户名或密码错误");
            }

            // 3. 检查账号状态
            if (user.getStatus() == 0) {
                return errorResponse(403, "账号已被禁用，请联系管理员");
            }

            // 4. 更新最后登录时间
            user.setLastLoginAt(LocalDateTime.now());
            userRepository.save(user);

            // 5. 生成 token
            String token = jwtUtil.generateToken(Long.valueOf(user.getId()), user.getUsername(), user.getRole());
            String refreshToken = jwtUtil.generateRefreshToken(Long.valueOf(user.getId()), user.getUsername(), user.getRole());

            System.out.println("✅ [自动登录成功] username=" + username + ", userId=" + user.getId());

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "登录成功");

            Map<String, Object> data = new HashMap<>();
            data.put("token", token);
            data.put("refreshToken", refreshToken);
            data.put("role", user.getRole());
            data.put("username", user.getUsername());
            data.put("email", user.getEmail() != null ? user.getEmail() : "");
            data.put("avatar", user.getAvatarUrl() != null ? user.getAvatarUrl() : "/api/avatars/default-avatar.png");
            data.put("studentId", user.getStudentId() != null ? user.getStudentId() : "");
            data.put("major", user.getMajor() != null ? user.getMajor() : "");
            data.put("college", user.getCollege() != null ? user.getCollege() : "");
            data.put("grade", user.getGrade() != null ? user.getGrade() : "");
            data.put("gender", user.getGender());
            data.put("genderText", user.getGenderText());

            response.put("data", data);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("自动登录失败: {}", e.getMessage(), e);
            return errorResponse(500, "登录失败，请稍后重试");
        }
    }

    private static Map<String, Object> getStringObjectMap(String token, String refreshToken, User user) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "登录成功");

        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("refreshToken", refreshToken);
        data.put("role", user.getRole());
        data.put("username", user.getUsername());
        data.put("email", user.getEmail() != null ? user.getEmail() : "");
        data.put("avatar", user.getAvatarUrl() != null ? user.getAvatarUrl() : "/api/avatars/default-avatar.png");
        data.put("studentId", user.getStudentId() != null ? user.getStudentId() : "");
        data.put("major", user.getMajor() != null ? user.getMajor() : "");
        data.put("college", user.getCollege() != null ? user.getCollege() : "");
        data.put("grade", user.getGrade() != null ? user.getGrade() : "");
        data.put("gender", user.getGender());
        data.put("genderText", user.getGenderText());

        response.put("data", data);
        return response;
    }

    // ==================== 刷新 Token 接口 ====================
    @PostMapping("/auth/refresh")
    public ResponseEntity<?> refreshToken(@RequestBody Map<String, String> request) {
        try {
            String refreshToken = request.get("refreshToken");

            if (refreshToken == null || refreshToken.isEmpty()) {
                return errorResponse(400, "refreshToken 不能为空");
            }

            // 验证 refresh token
            if (!jwtUtil.validateRefreshToken(refreshToken)) {
                // ✅ refresh token 过期或无效
                // 前端收到这个响应后，会尝试用保存的密码自动登录
                return errorResponse(401, "refresh token 无效或已过期");
            }

            Long userId = jwtUtil.getUserIdFromToken(refreshToken);
            Optional<User> userOptional = userRepository.findById(userId.intValue());

            if (userOptional.isEmpty()) {
                return errorResponse(404, "用户不存在");
            }

            User user = userOptional.get();

            // 生成新的 access token
            String newToken = jwtUtil.generateToken(userId, user.getUsername(), user.getRole());

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "Token 刷新成功");

            Map<String, Object> data = new HashMap<>();
            data.put("token", newToken);

            response.put("data", data);

            System.out.println("✅ Token 刷新成功: userId=" + userId);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("刷新 token 失败: {}", e.getMessage(), e);
            return errorResponse(500, "刷新 token 失败：" + e.getMessage());
        }
    }

    // ==================== 基础辅助方法 ====================
    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private Integer getIntegerValue(Map<String, Object> map) {
        Object value = map.get("gender");
        if (value == null) return 0;
        if (value instanceof Integer) return (Integer) value;
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private ResponseEntity<?> errorResponse(int code, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", code);
        response.put("message", message);
        response.put("data", null);
        return ResponseEntity.status(code).body(response);
    }

    @GetMapping("/auth/verify")
    public ApiResponse<Void> verifyToken(@RequestHeader("Authorization") String authHeader) {
        // 直接调用你的验证方法，如果抛异常就会被全局异常处理捕获
        Integer userId = extractUserIdFromToken(authHeader);
        return ApiResponse.success("Token有效", null);
    }

    // ==================== 从Token解析userId的核心方法 ====================

    /**
     * 从Authorization头中提取并解析userId
     * 没有默认值！token无效直接抛异常！
     */
    private Integer extractUserIdFromToken(String authHeader) {
        // 1. 验证token是否存在
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("未提供Token或Token格式错误");
        }

        // 2. 提取token
        String token = authHeader.substring(7);

        try {
            // 3. 解析token获取userId
            Long userIdLong = jwtUtil.getUserIdFromToken(token);

            if (userIdLong == null) {
                throw new RuntimeException("Token中不存在userId");
            }
            return userIdLong.intValue();

        } catch (Exception e) {
            throw new RuntimeException("无效的Token", e);
        }
    }

    @PutMapping("/user/basic-info")
    public ResponseEntity<?> updateUserBasicInfo(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> request) {
        try {
            // 解析token获取用户ID
            Integer userId = extractUserIdFromToken(authHeader);

            Optional<User> userOptional = userRepository.findById(userId);
            if (userOptional.isEmpty()) {
                return errorResponse(404, "用户不存在");
            }

            User user = userOptional.get();

            // 更新字段（只更新允许修改的字段）
            if (request.containsKey("gender")) {
                user.setGender((Integer) request.get("gender"));
            }
            if (request.containsKey("studentId")) {
                user.setStudentId((String) request.get("studentId"));
            }
            if (request.containsKey("major")) {
                user.setMajor((String) request.get("major"));
            }
            if (request.containsKey("college")) {
                user.setCollege((String) request.get("college"));
            }
            if (request.containsKey("grade")) {
                user.setGrade((String) request.get("grade"));
            }

            // 保存更新
            userRepository.save(user);

            // 返回更新后的用户信息
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "资料更新成功");
            response.put("data", user);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            return errorResponse(401, e.getMessage());
        } catch (Exception e) {
            logger.error("操作失败，原因: {}", e.getMessage(), e);
            return errorResponse(500, "更新失败：" + e.getMessage());
        }
    }

    @PostMapping("/user/avatar")
    public ResponseEntity<?> updateUserAvatar(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam("avatar") MultipartFile file) {
        try {
            // 解析token获取用户ID
            Integer userId = extractUserIdFromToken(authHeader);

            Optional<User> userOptional = userRepository.findById(userId);
            if (userOptional.isEmpty()) {
                return errorResponse(404, "用户不存在");
            }

            User user = userOptional.get();

            // 验证文件
            if (file.isEmpty()) {
                return errorResponse(400, "请选择要上传的文件");
            }

            // 验证文件类型
            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                return errorResponse(400, "只能上传图片文件");
            }

            // 验证文件大小（2MB）
            if (file.getSize() > 2 * 1024 * 1024) {
                return errorResponse(400, "图片大小不能超过2MB");
            }

            // 生成文件名
            String originalFilename = file.getOriginalFilename();
            String extension = originalFilename != null ?
                    originalFilename.substring(originalFilename.lastIndexOf(".")) : ".jpg";
            String fileName = "avatar_" + userId + "_" + System.currentTimeMillis() + extension;

            // 存储路径
            String uploadDir = "/opt/smart-campus/uploads/avatars/";
            File dir = new File(uploadDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            // 保存文件
            File destFile = new File(uploadDir + fileName);
            file.transferTo(destFile);

            // 生成访问URL
            String avatarUrl = "/api/uploads/avatars/" + fileName;

            // 更新到数据库
            user.setAvatarUrl(avatarUrl);
            userRepository.save(user);  // 这里会保存到数据库

            // 返回结果
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "头像上传成功");

            Map<String, Object> data = new HashMap<>();
            data.put("avatarUrl", avatarUrl);
            response.put("data", data);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            return errorResponse(401, e.getMessage());
        } catch (IOException e) {
            logger.error("操作失败，原因: {}", e.getMessage(), e);
            return errorResponse(500, "文件上传失败：" + e.getMessage());
        } catch (Exception e) {
            logger.error("操作失败，原因: {}", e.getMessage(), e);
            return errorResponse(500, "头像更新失败：" + e.getMessage());
        }
    }

    /**
     * 验证邮箱格式（支持所有常见邮箱）
     * 规则：
     * - 本地部分：字母、数字、点、下划线、连字符
     * - 域名部分：字母、数字、连字符、点
     */
    private boolean isValidEmail(String email) {
        if (email == null || email.trim().isEmpty()) {
            return false;
        }

        String trimmedEmail = email.trim();

        // 基本格式：必须包含 @ 和 .
        if (!trimmedEmail.contains("@") || !trimmedEmail.contains(".")) {
            return false;
        }

        // 正则表达式：支持常见邮箱格式
        // 本地部分：允许字母、数字、点、下划线、连字符
        // 域名部分：允许字母、数字、连字符、点
        String emailRegex = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$";

        return trimmedEmail.matches(emailRegex);
    }

}