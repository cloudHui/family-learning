package cc.ccwu.familylearning.service;

import cc.ccwu.familylearning.model.Student;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {
    private final StudentService students;
    private final UsageService usage;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public AuthService(StudentService students, UsageService usage) { this.students = students; this.usage = usage; }

    public LoginResult login(String username, String password, String device) throws Exception {
        Student user = students.findByUsername(username);
        boolean created = false;
        if (user == null) {
            if (!StudentService.DEFAULT_PASSWORD.equals(password)) throw new IllegalArgumentException("新用户首次登录密码为123456");
            user = students.create(username, username, password, "USER", StudentService.DEFAULT_PERMISSIONS);
            created = true;
        }
        if (!user.enabled) throw new IllegalArgumentException("账号已停用");
        if (!students.passwordMatches(user, password)) throw new IllegalArgumentException("用户名或密码不正确");
        students.recordLogin(user);
        String token = UUID.randomUUID().toString().replace("-", "");
        sessions.put(token, new Session(user.id));
        usage.login(user, device, created);
        return new LoginResult(token, students.view(user));
    }

    public void logout(String token) { Session session = sessions.remove(token); if (session != null) usage.logout(session.userId); }

    public Student require(String token) throws Exception {
        if (token == null || token.trim().isEmpty()) throw new SecurityException("请先登录");
        Session session = sessions.get(token);
        if (session == null || session.expiresAt.isBefore(LocalDateTime.now())) { sessions.remove(token); throw new SecurityException("登录已过期，请重新登录"); }
        Student user = students.get(session.userId);
        if (user == null || !user.enabled) throw new SecurityException("账号不可用");
        session.expiresAt = LocalDateTime.now().plusDays(7);
        return user;
    }

    public Student requireAdmin(String token) throws Exception {
        Student user = require(token);
        requireChangedPassword(user);
        if (!"ADMIN".equals(user.role)) throw new SecurityException("需要管理员权限");
        return user;
    }

    public Student requirePermission(String token, String permission) throws Exception {
        Student user = require(token);
        requireChangedPassword(user);
        if (!"ADMIN".equals(user.role) && (user.permissions == null || !user.permissions.contains(permission)))
            throw new SecurityException("该功能未开放");
        return user;
    }

    @Scheduled(fixedDelay = 3600000)
    public void removeExpiredSessions() {
        LocalDateTime now = LocalDateTime.now();
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt.isBefore(now));
    }

    public void requireSelfOrAdmin(Student current, String userId) {
        if (!current.id.equals(userId) && !"ADMIN".equals(current.role)) throw new SecurityException("不能访问其他用户数据");
    }

    private void requireChangedPassword(Student user) {
        if (user.mustChangePassword) throw new SecurityException("请先修改初始密码");
    }

    public static class Session {
        public String userId; public LocalDateTime expiresAt;
        public Session(String userId) { this.userId = userId; this.expiresAt = LocalDateTime.now().plusDays(7); }
    }
    public static class LoginResult {
        public String token; public Map<String, Object> user;
        public LoginResult(String token, Map<String, Object> user) { this.token = token; this.user = user; }
    }
}
