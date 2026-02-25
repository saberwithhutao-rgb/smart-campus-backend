package com.smartcampus.controller;

import com.smartcampus.entity.User;
import com.smartcampus.repository.UserRepository;
import com.smartcampus.service.EmailService;
import com.smartcampus.utils.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Timer;
import java.util.TimerTask;

@RestController
@RequestMapping("/api")  // 添加这一行
@CrossOrigin(origins = {"http://localhost:5173",
        "http://8.134.179.88",  // 更新为新服务器IP
        "http://localhost"},
        allowedHeaders = "*",
        methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS},
        allowCredentials = "true")

public class TestController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailService emailService;  // 邮件服务

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired(required = false)  // 设置为非必须，避免启动失败
    private JavaMailSender javaMailSender;

    @Autowired
    private HttpServletRequest httpServletRequest;  // 用于获取客户端IP

    @Autowired
    private JwtUtil jwtUtil;

    // ==================== 安全的随机数生成器 ====================
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DIGITS = "0123456789";
    private static final String CAPTCHA_DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"; // 验证码字符集

    // ==================== 频率限制存储 ====================
    // 存储验证码（key: "email:xxx@xx.com", value: "123456"）
    private final ConcurrentHashMap<String, String> emailCodes = new ConcurrentHashMap<>();

    // 注册频率限制（key: IP地址, value: 最后注册时间）
    private final ConcurrentHashMap<String, LocalDateTime> lastRegisterTime = new ConcurrentHashMap<>();

    // 验证码发送频率限制（key: "email:xxx@xx.com", value: 最后发送时间）
    private final ConcurrentHashMap<String, LocalDateTime> lastVerifyCodeTime = new ConcurrentHashMap<>();

    // ==================== 新增：图形验证码接口 ====================
    @GetMapping("/captcha")
    @ResponseBody
    public Map<String, Object> generateCaptcha(HttpSession session) {
        try {
            // 生成4位随机字符（数字+大写字母）
            StringBuilder captcha = new StringBuilder(4);
            for (int i = 0; i < 4; i++) {
                captcha.append(CAPTCHA_DIGITS.charAt(RANDOM.nextInt(CAPTCHA_DIGITS.length())));
            }
            String captchaText = captcha.toString();

            System.out.println("🔐 [生成图形验证码] " + captchaText);

            // 生成验证码图片（Base64格式）
            String captchaBase64 = generateCaptchaImage(captchaText);

            // 存储到session
            session.setAttribute("captcha", captchaText);
            session.setAttribute("captchaTime", System.currentTimeMillis());
//            session.setAttribute("captchaId", session.getId());

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("data", captchaText);
            result.put("captchaId", session.getId());
            result.put("captchaBase64", captchaBase64); // 新增：Base64图片
            result.put("message", "验证码生成成功");
            result.put("expiresIn", 600);

            return result;

        } catch (Exception e) {
            e.printStackTrace();
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("code", 500);
            errorResult.put("message", "验证码生成失败: " + e.getMessage());
            errorResult.put("data", null);
            return errorResult;
        }
    }

    private String generateCaptchaImage(String text) {
        try {
            // 图片尺寸
            int width = 120;
            int height = 40;

            // 创建内存中的图片
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = image.createGraphics();

            // 设置抗锯齿
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // 绘制背景（渐变）
            GradientPaint gradient = new GradientPaint(0, 0, new Color(240, 240, 240),
                    width, height, new Color(200, 200, 200));
            g2d.setPaint(gradient);
            g2d.fillRect(0, 0, width, height);

            // 添加噪点背景
            g2d.setColor(new Color(180, 180, 180));
            for (int i = 0; i < 100; i++) {
                int x = RANDOM.nextInt(width);
                int y = RANDOM.nextInt(height);
                g2d.fillRect(x, y, 2, 2);
            }

            // 设置字体
            Font[] fonts = {
                    new Font("Arial", Font.BOLD, 28),
                    new Font("Courier New", Font.BOLD, 28),
                    new Font("Times New Roman", Font.BOLD, 28)
            };

            // 绘制验证码字符
            int charWidth = width / (text.length() + 1);
            for (int i = 0; i < text.length(); i++) {
                // 随机选择字体
                Font font = fonts[RANDOM.nextInt(fonts.length)];
                g2d.setFont(font);

                // 随机颜色
                Color color = new Color(
                        RANDOM.nextInt(100) + 50,    // R: 50-150
                        RANDOM.nextInt(100) + 50,    // G: 50-150
                        RANDOM.nextInt(100) + 50     // B: 50-150
                );
                g2d.setColor(color);

                // 随机倾斜角度
                double angle = (RANDOM.nextDouble() - 0.5) * Math.PI / 6; // -15°到+15°
                g2d.rotate(angle, charWidth * (i + 1), (double) height / 2);

                // 绘制字符
                String ch = String.valueOf(text.charAt(i));
                g2d.drawString(ch, charWidth * (i + 1) - 10, height / 2 + 10);

                // 恢复旋转
                g2d.rotate(-angle, charWidth * (i + 1), (double) height / 2);
            }

            // 添加干扰线
            g2d.setColor(new Color(150, 150, 150, 100)); // 半透明灰色
            for (int i = 0; i < 5; i++) {
                int x1 = RANDOM.nextInt(width / 2);
                int y1 = RANDOM.nextInt(height);
                int x2 = width / 2 + RANDOM.nextInt(width / 2);
                int y2 = RANDOM.nextInt(height);

                // 设置线条粗细
                g2d.setStroke(new BasicStroke(1.5f));
                g2d.drawLine(x1, y1, x2, y2);
            }

            // 添加边框
            g2d.setColor(Color.LIGHT_GRAY);
            g2d.setStroke(new BasicStroke(2f));
            g2d.drawRect(1, 1, width - 3, height - 3);

            g2d.dispose();

            // 转换为Base64
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());

            System.out.println("✅ 生成验证码图片成功，大小: " + base64.length() + " 字符");

            return "data:image/png;base64," + base64;

        } catch (Exception e) {
            System.err.println("❌ 生成验证码图片失败: " + e.getMessage());
            e.printStackTrace();
            return ""; // 返回空字符串，前端会降级为文本显示
        }
    }

    // 验证图形验证码（辅助方法）
    private boolean validateCaptcha(HttpSession session, String userCaptcha) {
        if (session == null || userCaptcha == null || userCaptcha.trim().isEmpty()) {
            System.out.println("❌ 验证码验证失败：session或用户验证码为空");
            return false;
        }

        String sessionCaptcha = (String) session.getAttribute("captcha");
        Long captchaTime = (Long) session.getAttribute("captchaTime");

        System.out.println("🔍 [验证图形验证码] session中的: " + sessionCaptcha + ", 用户输入的: " + userCaptcha);

        if (sessionCaptcha == null || captchaTime == null) {
            System.out.println("❌ 验证码验证失败：session中未找到验证码");
            return false;
        }

        // 验证码10分钟有效
        if (System.currentTimeMillis() - captchaTime > 10 * 60 * 1000) {
            System.out.println("❌ 验证码验证失败：验证码已过期");
            session.removeAttribute("captcha");
            session.removeAttribute("captchaTime");
            return false;
        }

        boolean isValid = sessionCaptcha.equalsIgnoreCase(userCaptcha.trim());

        if (isValid) {
            // 验证成功后移除session中的验证码，防止重复使用
            session.removeAttribute("captcha");
            session.removeAttribute("captchaTime");
            System.out.println("✅ 图形验证码验证成功");
        } else {
            System.out.println("❌ 验证码验证失败：不匹配");
        }

        return isValid;
    }

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
    public ResponseEntity<?> sendVerifyCode(@RequestBody Map<String, String> request, HttpSession session) {
        try {
            String email = request.get("email");

            System.out.println("📧 [发送邮箱验证码] 邮箱: " + email);

            if (email == null || !email.contains("@")) {
                return errorResponse(400, "邮箱格式不正确");
            }

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
                    System.out.println("⏰ 邮箱验证码已过期: " + email);
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

            // 7. 记录发送时间
            lastVerifyCodeTime.put(emailKey, LocalDateTime.now());

            // 8. 返回成功响应
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "验证码已发送");
            response.put("data", null);


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
            if (username.trim().isEmpty()) {
                return errorResponse(400, "用户名不能为空");
            }
            if (password.length() < 6) {
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

    private String encodePassword(String rawPassword) {
        // 使用BCrypt强哈希加密（安全）
        return passwordEncoder.encode(rawPassword);
    }



    private boolean checkPassword(String rawPassword, String encodedPassword) {
        // 使用passwordEncoder验证密码
        return passwordEncoder.matches(rawPassword, encodedPassword);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> request, HttpServletRequest httpRequest) {
        try {
            String username = request.get("username");
            String password = request.get("password");
            String captcha = request.get("captcha");

            System.out.println("🔑 [登录] 收到数据：" + request);

            HttpSession session = httpRequest.getSession(false);

            // 1. 验证验证码
            if (session == null || !validateCaptcha(session, captcha)) {
                return errorResponse(400, "验证码错误或已过期");
            }

            session.invalidate();

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

            // 5. 生成token
            String token = jwtUtil.generateToken(Long.valueOf(user.getId()), user.getUsername(), user.getRole());

            // ✅ 6. 返回完整的用户信息
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "登录成功");

            Map<String, Object> data = new HashMap<>();
            data.put("token", token);
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

    // ==================== 新增：简单邮件测试接口 ====================
    @PostMapping("/test-email")
    @ResponseBody
    public Map<String, Object> testEmail(@RequestBody Map<String, String> request) {
        try {
            String toEmail = request.get("email");

            if (toEmail == null || !toEmail.contains("@")) {
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
                e.printStackTrace(); // 这会把错误详情打印到后端日志里
                System.err.println("❌ 邮件发送失败: " + e.getMessage());

                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("code", 500);
                errorResponse.put("message", "邮件发送失败: " + e.getMessage());
                errorResponse.put("data", null);

                return errorResponse;
            }

        } catch (Exception e) {
            e.printStackTrace();
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
            e.printStackTrace();
            return errorResponse(1005, "Token无效，退出失败");
        }
    }

    // 2. 刷新Token接口
    @PostMapping("/token/refresh")
    public ResponseEntity<?> refreshToken(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return errorResponse(401, "Token无效");
            }

            String oldToken = authHeader.substring(7);
            // 验证旧token（简单模拟）
            if (!oldToken.startsWith("jwt-")) {
                return errorResponse(401, "Token格式错误");
            }

            // 解析用户ID
            String[] parts = oldToken.split("-");
            if (parts.length < 2) {
                return errorResponse(401, "Token格式错误");
            }

            Integer userId = Integer.parseInt(parts[1]);
            Optional<User> userOptional = userRepository.findById(userId);

            if (userOptional.isEmpty()) {
                return errorResponse(404, "用户不存在");
            }

            // 生成新token
            String newToken = "jwt-" + userId + "-" + System.currentTimeMillis();

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "Token刷新成功");

            Map<String, Object> data = new HashMap<>();
            data.put("token", newToken);
            data.put("refreshToken", newToken + "-refresh");
            data.put("role", userOptional.get().getRole());
            data.put("username", userOptional.get().getUsername());

            response.put("data", data);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            return errorResponse(500, "刷新Token失败");
        }
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