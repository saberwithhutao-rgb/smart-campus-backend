package com.smartcampus.utils;

import com.smartcampus.entity.User;
import com.smartcampus.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Component
@Slf4j
public class TokenGenerator implements CommandLineRunner {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserRepository userRepository;

    @Override
    public void run(String... args) throws Exception {
        // 应用启动时打印一些测试token
        List<User> users = userRepository.findAll();

        if (!users.isEmpty()) {
            log.info("=== 测试Token生成（开发环境使用）===");
            for (User user : users) {
                String token = jwtUtil.generateToken(
                        Long.valueOf(user.getId()),
                        user.getUsername(),
                        user.getRole()
                );
                log.info("用户: {} (ID: {})", user.getUsername(), user.getId());
                log.info("Token: {}", token);
                log.info("cURL测试命令:");
                log.info("curl -X POST http://localhost:8080/ai/chat \\");
                log.info("  -H \"Authorization: Bearer {} \\", token);
                log.info("  -F \"question=测试问题\"");
                log.info("---");
            }
        }
    }
}