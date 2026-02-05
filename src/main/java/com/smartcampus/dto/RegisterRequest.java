package com.smartcampus.dto;

public class RegisterRequest {
    private String username;
    private String password;
    private String email;
    private String verifyCode;
    private String studentId;
    private String major;
    private String college;
    private String grade;
    private Integer gender;

    public RegisterRequest() {}

    // Getter和Setter
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getVerifyCode() { return verifyCode; }
    public void setVerifyCode(String verifyCode) { this.verifyCode = verifyCode; }

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public String getMajor() { return major; }
    public void setMajor(String major) { this.major = major; }

    public String getCollege() { return college; }
    public void setCollege(String college) { this.college = college; }

    public String getGrade() { return grade; }
    public void setGrade(String grade) { this.grade = grade; }

    public Integer getGender() { return gender; }
    public void setGender(Integer gender) { this.gender = gender; }
}