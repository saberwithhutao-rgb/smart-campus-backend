package com.smartcampus.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Service
public class EmailService {
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    @Autowired
    private JavaMailSender mailSender;

    @Value("${spring.mail.username}")  // ✅ 从配置读取发件邮箱
    private String fromEmail;
    /**
     * 发送纯文本邮件
     */
    public void sendSimpleEmail(String to, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);  // ✅ 使用配置的发件邮箱
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);

        mailSender.send(message);
        System.out.println("📧 纯文本邮件已发送到: " + to);
    }

    /**
     * 发送HTML格式邮件（验证码）
     */
    public void sendHtmlEmail(String to, String subject, String htmlContent) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setFrom(fromEmail);  // ✅ 使用配置的发件邮箱
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(htmlContent, true);

        mailSender.send(message);
        System.out.println("📧 HTML邮件已发送到: " + to);
    }

    /**
     * 发送验证码邮件
     */
    public void sendVerificationCode(String to, String code) {
        try {
            String subject = "【智慧校园】邮箱验证码";
            String htmlContent = buildVerificationEmailHtml(code);

            sendHtmlEmail(to, subject, htmlContent);
            System.out.println("✅ 验证码邮件发送成功: " + code);

        } catch (Exception e) {
            log.error("邮件发送失败，使用模拟验证码。收件人: {}, 错误: {}", to, e.getMessage());
        }
    }

    /**
     * 构建验证码邮件的HTML内容
     */
    private String buildVerificationEmailHtml(String code) {
        return """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>邮箱验证码</title>
                <style>
                    body {
                        font-family: 'Microsoft YaHei', Arial, sans-serif;
                        line-height: 1.6;
                        color: #333;
                        max-width: 600px;
                        margin: 0 auto;
                        padding: 20px;
                    }
                    .container {
                        background-color: #f9f9f9;
                        border-radius: 10px;
                        padding: 30px;
                        box-shadow: 0 2px 10px rgba(0,0,0,0.1);
                    }
                    .header {
                        text-align: center;
                        margin-bottom: 30px;
                    }
                    .logo {
                        font-size: 24px;
                        font-weight: bold;
                        color: #409EFF;
                        margin-bottom: 10px;
                    }
                    .code-box {
                        background-color: #fff;
                        border: 2px dashed #409EFF;
                        border-radius: 8px;
                        padding: 20px;
                        text-align: center;
                        margin: 30px 0;
                    }
                    .verification-code {
                        font-size: 32px;
                        font-weight: bold;
                        color: #409EFF;
                        letter-spacing: 5px;
                        margin: 10px 0;
                    }
                    .tip {
                        background-color: #f0f9ff;
                        border-left: 4px solid #409EFF;
                        padding: 15px;
                        margin: 20px 0;
                        font-size: 14px;
                        color: #666;
                    }
                    .footer {
                        text-align: center;
                        margin-top: 30px;
                        padding-top: 20px;
                        border-top: 1px solid #eee;
                        font-size: 12px;
                        color: #999;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <div class="logo">智慧校园平台</div>
                        <h2>邮箱验证码</h2>
                    </div>
            
                    <p>您好！</p>
                    <p>您正在进行邮箱验证操作，验证码如下：</p>
            
                    <div class="code-box">
                        <div class="verification-code">%s</div>
                        <p>（有效期10分钟）</p>
                    </div>
            
                    <div class="tip">
                        <strong>温馨提示：</strong>
                        <ul>
                            <li>请勿将验证码告知他人</li>
                            <li>如非本人操作，请忽略此邮件</li>
                            <li>验证码将在10分钟后失效</li>
                        </ul>
                    </div>
            
                    <p>感谢您使用智慧校园平台！</p>
                    <p>如果这不是您请求的操作，请忽略此邮件。</p>
            
                    <div class="footer">
                        <p>此为系统邮件，请勿直接回复</p>
                        <p>© 2026 智慧校园平台 版权所有</p>
                    </div>
                </div>
            </body>
            </html>
            """.formatted(code);
    }
}