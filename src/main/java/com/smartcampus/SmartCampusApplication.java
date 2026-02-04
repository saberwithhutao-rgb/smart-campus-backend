// 文件 2: SmartCampusApplication.java 的内容
package com.smartcampus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SmartCampusApplication {
    public static void main(String[] args) {
        SpringApplication.run(SmartCampusApplication.class, args);
        System.out.println("✅ 后端启动成功！");
    }
}