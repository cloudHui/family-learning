package cc.ccwu.familylearning.service;

import cc.ccwu.familylearning.model.Student;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

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
        Session session = new Session(user, device);
        sessions.put(token, session);
        usage.login(user, device, false);
        log.info("用户登录 username={}, name={}, ip={}, device={}", session.username, session.name, session.lastIp, session.device);
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
        Session session = new Session(user, device);
        sessions.put(token, session);
        usage.login(user, device, true);
        log.info("用户注册登录 username={}, name={}, ip={}, device={}, invite={}",
                session.username, session.name, session.lastIp, session.device, hasInvite);
        return new LoginResult(token, students.view(user));
    }

    public void logout(String token) {
        logout(token, null);
    }

    public void logout(String token, String reason) {
        String label = reason == null || reason.trim().isEmpty() ? "主动退出" : reason.trim();
        if ("idle".equalsIgnoreCase(label) || "空闲超时".equals(label)) label = "空闲超时(前端)";
        endSession(token, label, ClientIp.current());
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
        String ip = ClientIp.current();
        if (session.lastActivityAt.plus(idleTimeout).isBefore(now)) {
            endSession(token, "空闲超时", ip);
            throw new SecurityException("已超过" + idleTimeout.toMinutes() + "分钟未操作，请重新登录");
        }
        Student user = students.get(session.userId);
        if (user == null || !user.enabled) {
            endSession(token, "账号不可用", ip);
            throw new SecurityException("账号不可用");
        }
        session.lastIp = ip;
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
        for (Map.Entry<String, Session> entry : sessions.entrySet()) {
            Session session = entry.getValue();
            if (session.lastActivityAt.plus(idleTimeout).isBefore(now)) {
                endSession(entry.getKey(), "空闲超时(定时清理)", session.lastIp == null ? "-" : session.lastIp);
            }
        }
    }

    public void requireSelfOrAdmin(Student current, String userId) {
        if (!current.id.equals(userId) && !"ADMIN".equals(current.role)) throw new SecurityException("不能访问其他用户数据");
    }

    private void endSession(String token, String reason, String ip) {
        if (token == null) return;
        Session session = sessions.remove(token);
        if (session == null) return;
        usage.logout(session.userId);
        String shownIp = resolveLogIp(ip, session.lastIp);
        log.info("用户掉线 reason={}, username={}, name={}, userId={}, ip={}, device={}, lastActivityAt={}",
                reason, session.username, session.name, session.userId,
                shownIp, session.device, session.lastActivityAt);
    }

    /** 掉线日志优先用当前请求 IP；无效或本机回环时回退会话缓存。 */
    private static String resolveLogIp(String current, String cached) {
        String now = ClientIp.normalize(current);
        String prev = ClientIp.normalize(cached);
        if (ClientIp.isValidIp(now) && !ClientIp.isLoopback(now)) return now;
        if (ClientIp.isValidIp(prev) && !ClientIp.isLoopback(prev)) return prev;
        if (ClientIp.isValidIp(now)) return now;
        if (ClientIp.isValidIp(prev)) return prev;
        return "-";
    }

    private void requireChangedPassword(Student user) {
        if (user.mustChangePassword) throw new SecurityException("请先修改初始密码");
    }

    private String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static class Session {
        public String userId;
        public String username;
        public String name;
        public String device;
        public String lastIp;
        public LocalDateTime lastActivityAt;

        public Session(Student user, String device) {
            this.userId = user.id;
            this.username = user.username;
            this.name = user.name;
            this.device = device == null || device.trim().isEmpty() ? "未知设备" : device.trim();
            this.lastIp = ClientIp.current();
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
