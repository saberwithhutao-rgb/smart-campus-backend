package com.smartcampus.utils;

// import com.smartcampus.entity.User;
// import com.smartcampus.repository.UserRepository;
// import org.springframework.beans.factory.annotation.Autowired;
// import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DatabaseInit { // implements CommandLineRunner {
    /*
    @Autowired
    private UserRepository userRepository;

    @Override
    public void run(String... args) throws Exception {
        System.out.println("✅ 数据库初始化检查...");
        // 暂时不初始化数据
    }
    */

    public void initDefaultUsers() {
        System.out.println("🔧 默认用户初始化功能已禁用");
    }
}