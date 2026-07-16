package cc.ccwu.familylearning.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class Student {
    public String id;
    public String username;
    public String name;
    public String passwordHash;
    public String role;
    public boolean enabled;
    public List<String> permissions = new ArrayList<>();
    public String stage;
    public LocalDateTime createdAt;
    public LocalDateTime lastActiveAt;
    public LocalDateTime lastLoginAt;
    public LocalDateTime passwordChangedAt;
    public boolean mustChangePassword;
    public long loginCount;

    public Student() {}

    public Student(String id, String username, String name) {
        this.id = id;
        this.username = username;
        this.name = name;
        this.role = "USER";
        this.enabled = true;
        this.stage = "幼小衔接";
        this.createdAt = LocalDateTime.now();
        this.lastActiveAt = this.createdAt;
    }
}
