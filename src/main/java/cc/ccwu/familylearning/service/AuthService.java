package cc.ccwu.familylearning.service;

import cc.ccwu.familylearning.model.Student;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {
    private final StudentService students;
    private final UsageService usage;
    private final InviteService invites;
    private final boolean openRegister;
    private final Duration idleTimeout;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public AuthService(StudentService students, UsageService usage, InviteService invites,
                       @Value("${family-learning.registration.open-enabled:true}") boolean openRegister,
                       @Value("${family-learning.session.idle-minutes:10}") int idleMinutes) {
        this.students = students;
        this.usage = usage;
        this.invites = invites;
        this.openRegister = openRegister;
        this.idleTimeout = Duration.ofMinutes(Math.max(1, idleMinutes));
    }

    public Map<String, Object> registrationOptions() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("openRegister", openRegister);
        result.put("idleMinutes", idleTimeout.toMinutes());
        return result;
    }

    public LoginResult login(String username, String password, String device) throws Exception {
        Student user = students.findByUsername(username);
        if (user == null) throw new IllegalArgumentException("用户名或密码不正确");
        if (!user.enabled) throw new IllegalArgumentException("账号已停用");
        if (!students.passwordMatches(user, password)) throw new IllegalArgumentException("用户名或密码不正确");
        students.recordLogin(user);
        String token = newToken();
        sessions.put(token, new Session(user.id));
        usage.login(user, device, false);
        return new LoginResult(token, students.view(user));
    }

    public LoginResult register(String username, String password, String name, String inviteToken, String device) throws Exception {
        boolean hasInvite = inviteToken != null && !inviteToken.trim().isEmpty();
        if (!openRegister && !hasInvite) throw new IllegalArgumentException("当前仅支持邀请链接注册，请使用分享的注册链接");
        if (hasInvite) invites.peekValid(inviteToken);
        if (students.findByUsername(username) != null) throw new IllegalArgumentException("用户名已存在");
        Student user = students.create(username, name == null || name.trim().isEmpty() ? username : name,
                password, "USER", StudentService.DEFAULT_PERMISSIONS);
        students.changePassword(user.id, password, password, false);
        user = students.get(user.id);
        if (hasInvite) invites.consume(inviteToken);
        students.recordLogin(user);
        String token = newToken();
        sessions.put(token, new Session(user.id));
        usage.login(user, device, true);
        return new LoginResult(token, students.view(user));
    }

    public void logout(String token) {
        if (token == null) return;
        Session session = sessions.remove(token);
        if (session != null) usage.logout(session.userId);
    }

    /** 普通接口：校验会话并刷新空闲计时。 */
    public Student require(String token) throws Exception {
        return require(token, true);
    }

    /** 心跳：校验会话但不刷新空闲计时。 */
    public Student requireHeartbeat(String token) throws Exception {
        return require(token, false);
    }

    public Student require(String token, boolean touchActivity) throws Exception {
        if (token == null || token.trim().isEmpty()) throw new SecurityException("请先登录");
        Session session = sessions.get(token);
        if (session == null) throw new SecurityException("登录已过期，请重新登录");
        LocalDateTime now = LocalDateTime.now();
        if (session.lastActivityAt.plus(idleTimeout).isBefore(now)) {
            sessions.remove(token);
            usage.logout(session.userId);
            throw new SecurityException("已超过" + idleTimeout.toMinutes() + "分钟未操作，请重新登录");
        }
        Student user = students.get(session.userId);
        if (user == null || !user.enabled) {
            sessions.remove(token);
            throw new SecurityException("账号不可用");
        }
        if (touchActivity) session.lastActivityAt = now;
        return user;
    }

    public Student requireAdmin(String token) throws Exception {
        Student user = require(token, true);
        requireChangedPassword(user);
        if (!"ADMIN".equals(user.role)) throw new SecurityException("需要管理员权限");
        return user;
    }

    public Student requirePermission(String token, String permission) throws Exception {
        Student user = require(token, true);
        requireChangedPassword(user);
        if (!"ADMIN".equals(user.role) && (user.permissions == null || !user.permissions.contains(permission)))
            throw new SecurityException("该功能未开放");
        return user;
    }

    @Scheduled(fixedDelay = 60000)
    public void removeExpiredSessions() {
        LocalDateTime now = LocalDateTime.now();
        sessions.entrySet().removeIf(entry -> {
            Session session = entry.getValue();
            boolean expired = session.lastActivityAt.plus(idleTimeout).isBefore(now);
            if (expired) usage.logout(session.userId);
            return expired;
        });
    }

    public void requireSelfOrAdmin(Student current, String userId) {
        if (!current.id.equals(userId) && !"ADMIN".equals(current.role)) throw new SecurityException("不能访问其他用户数据");
    }

    private void requireChangedPassword(Student user) {
        if (user.mustChangePassword) throw new SecurityException("请先修改初始密码");
    }

    private String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static class Session {
        public String userId;
        public LocalDateTime lastActivityAt;
        public Session(String userId) {
            this.userId = userId;
            this.lastActivityAt = LocalDateTime.now();
        }
    }

    public static class LoginResult {
        public String token;
        public Map<String, Object> user;
        public LoginResult(String token, Map<String, Object> user) {
            this.token = token;
            this.user = user;
        }
    }
}
