package cc.ccwu.familylearning.controller;

import cc.ccwu.familylearning.model.Student;
import cc.ccwu.familylearning.service.AuthService;
import cc.ccwu.familylearning.service.InviteService;
import cc.ccwu.familylearning.service.StudentService;
import cc.ccwu.familylearning.service.UsageService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final StudentService students;
    private final UsageService usage;
    private final InviteService invites;

    public AuthController(AuthService auth, StudentService students, UsageService usage, InviteService invites) {
        this.auth = auth;
        this.students = students;
        this.usage = usage;
        this.invites = invites;
    }

    @GetMapping("/registration")
    public Map<String, Object> registration(@RequestParam(value = "invite", required = false) String invite) throws Exception {
        Map<String, Object> result = auth.registrationOptions();
        if (invite != null && !invite.trim().isEmpty()) {
            invites.peekValid(invite);
            result.put("inviteValid", true);
        }
        return result;
    }

    @PostMapping("/login")
    public AuthService.LoginResult login(@RequestBody LoginRequest request) throws Exception {
        return auth.login(request.username, request.password, request.device);
    }

    @PostMapping("/register")
    public AuthService.LoginResult register(@RequestBody RegisterRequest request) throws Exception {
        return auth.register(request.username, request.password, request.name, request.invite, request.device);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = "X-Session-Token", required = false) String token,
                                      @RequestParam(value = "reason", required = false) String reason) {
        auth.logout(token, reason);
        return ok("已退出");
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader(value = "X-Session-Token", required = false) String token) throws Exception {
        return students.view(auth.require(token));
    }

    @PostMapping("/password")
    public Map<String, Object> password(@RequestHeader(value = "X-Session-Token", required = false) String token,
                                        @RequestBody PasswordRequest request) throws Exception {
        Student user = auth.require(token);
        students.changePassword(user.id, request.oldPassword, request.newPassword, false);
        return ok("密码已修改");
    }

    @PostMapping("/heartbeat")
    public Map<String, Object> heartbeat(@RequestHeader(value = "X-Session-Token", required = false) String token,
                                         @RequestBody HeartbeatRequest request) throws Exception {
        Student user = auth.requireHeartbeat(token);
        usage.heartbeat(user, request.page, request.feature, request.device);
        return ok("ok");
    }

    @PostMapping("/frontend-error")
    public Map<String, Object> frontendError(@RequestHeader(value = "X-Session-Token", required = false) String token) throws Exception {
        auth.require(token);
        usage.frontendError();
        return ok("ok");
    }

    private Map<String, Object> ok(String message) {
        return java.util.Collections.<String, Object>singletonMap("message", message);
    }

    public static class LoginRequest {
        public String username;
        public String password;
        public String device;
    }

    public static class RegisterRequest {
        public String username;
        public String password;
        public String name;
        public String invite;
        public String device;
    }

    public static class PasswordRequest {
        public String oldPassword;
        public String newPassword;
    }

    public static class HeartbeatRequest {
        public String page;
        public String feature;
        public String device;
    }
}
