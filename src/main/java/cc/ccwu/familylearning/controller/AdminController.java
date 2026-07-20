package cc.ccwu.familylearning.controller;

import cc.ccwu.familylearning.model.*;
import cc.ccwu.familylearning.service.*;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private final AuthService auth;private final StudentService students;private final WordService words;private final ContentService content;
    private final RecordService records;private final MistakeService mistakes;private final StatsService stats;private final UsageService usage;private final DailyReportService reports;
    private final InviteService invites;
    public AdminController(AuthService auth,StudentService students,WordService words,ContentService content,RecordService records,MistakeService mistakes,StatsService stats,UsageService usage,DailyReportService reports,InviteService invites){this.auth=auth;this.students=students;this.words=words;this.content=content;this.records=records;this.mistakes=mistakes;this.stats=stats;this.usage=usage;this.reports=reports;this.invites=invites;}
    private Student admin(String token)throws Exception{Student operator=auth.requireAdmin(token);log.info("管理员进入后台接口: operator={}, tokenPresent={}",operator.username,token!=null);return operator;}

    @GetMapping("/users") public List<Map<String,Object>> users(@RequestHeader("X-Session-Token")String token)throws Exception{admin(token);return students.list().stream().map(students::view).collect(Collectors.toList());}
    @PostMapping("/users") public Map<String,Object> createUser(@RequestHeader("X-Session-Token")String token,@RequestBody UserRequest request)throws Exception{admin(token);return students.view(students.create(request.username,request.name,request.password==null?StudentService.DEFAULT_PASSWORD:request.password,request.role,request.permissions));}
    @PutMapping("/users/{id}") public Map<String,Object> updateUser(@RequestHeader("X-Session-Token")String token,@PathVariable String id,@RequestBody Student changes)throws Exception{admin(token);return students.view(students.update(id,changes));}
    @DeleteMapping("/users/{id}") public Map<String,Object> deleteUser(@RequestHeader("X-Session-Token")String token,@PathVariable String id)throws Exception{admin(token);students.delete(id);return ok("用户已删除");}
    @PostMapping("/users/{id}/reset-password") public Map<String,Object> reset(@RequestHeader("X-Session-Token")String token,@PathVariable String id)throws Exception{admin(token);students.resetPassword(id);return ok("密码已重置为123456");}

    @GetMapping("/invites") public List<Invite> listInvites(@RequestHeader("X-Session-Token")String token)throws Exception{admin(token);return invites.list();}
    @PostMapping("/invites") public Invite createInvite(@RequestHeader("X-Session-Token")String token,@RequestBody InviteRequest request)throws Exception{
        Student operator=admin(token);
        return invites.create(operator.username, request==null?null:request.note, request==null||request.maxUses<=0?1:request.maxUses, request==null||request.validDays<=0?7:request.validDays);
    }
    @DeleteMapping("/invites/{id}") public Map<String,Object> revokeInvite(@RequestHeader("X-Session-Token")String token,@PathVariable String id)throws Exception{admin(token);invites.revoke(id);return ok("邀请已作废");}

    @GetMapping("/words") public List<WordItem> words(@RequestHeader("X-Session-Token")String token)throws Exception{admin(token);return words.list(null);}
    @PostMapping("/words") public WordItem saveWord(@RequestHeader("X-Session-Token")String token,@RequestBody WordItem item)throws Exception{admin(token);return words.save(item);}
    @DeleteMapping("/words/{id}") public Map<String,Object> deleteWord(@RequestHeader("X-Session-Token")String token,@PathVariable String id)throws Exception{admin(token);words.delete(id);return ok("汉字已删除");}

    @GetMapping("/content") public List<ContentItem> content(@RequestHeader("X-Session-Token")String token)throws Exception{admin(token);return content.content(null);}
    @PostMapping("/content") public ContentItem saveContent(@RequestHeader("X-Session-Token")String token,@RequestBody ContentItem item)throws Exception{admin(token);return content.saveContent(item);}
    @DeleteMapping("/content/{id}") public Map<String,Object> deleteContent(@RequestHeader("X-Session-Token")String token,@PathVariable String id)throws Exception{admin(token);content.deleteContent(id);return ok("内容已删除");}

    @GetMapping("/templates") public List<WordProblemTemplate> templates(@RequestHeader("X-Session-Token")String token)throws Exception{admin(token);return content.templates();}
    @PostMapping("/templates") public WordProblemTemplate saveTemplate(@RequestHeader("X-Session-Token")String token,@RequestBody WordProblemTemplate item)throws Exception{admin(token);return content.saveTemplate(item);}
    @DeleteMapping("/templates/{id}") public Map<String,Object> deleteTemplate(@RequestHeader("X-Session-Token")String token,@PathVariable String id)throws Exception{admin(token);content.deleteTemplate(id);return ok("模板已删除");}

    @GetMapping("/records/{userId}") public List<LearningRecord> records(@RequestHeader("X-Session-Token")String token,@PathVariable String userId)throws Exception{admin(token);return records.list(userId);}
    @PostMapping("/records/{userId}") public LearningRecord addRecord(@RequestHeader("X-Session-Token")String token,@PathVariable String userId,@RequestBody LearningRecord item)throws Exception{admin(token);item.studentId=userId;return records.add(item);}
    @PutMapping("/records/{userId}/{id}") public LearningRecord updateRecord(@RequestHeader("X-Session-Token")String token,@PathVariable String userId,@PathVariable String id,@RequestBody LearningRecord item)throws Exception{admin(token);return records.update(userId,id,item);}
    @DeleteMapping("/records/{userId}/{id}") public Map<String,Object> deleteRecord(@RequestHeader("X-Session-Token")String token,@PathVariable String userId,@PathVariable String id)throws Exception{admin(token);records.delete(userId,id);return ok("记录已删除");}
    @GetMapping("/mistakes/{userId}") public List<Mistake> mistakes(@RequestHeader("X-Session-Token")String token,@PathVariable String userId)throws Exception{admin(token);return mistakes.list(userId,null,null);}
    @PostMapping("/mistakes/{userId}") public Mistake addMistake(@RequestHeader("X-Session-Token")String token,@PathVariable String userId,@RequestBody Mistake item)throws Exception{admin(token);item.studentId=userId;return mistakes.add(item);}
    @PutMapping("/mistakes/{userId}/{id}") public Mistake updateMistake(@RequestHeader("X-Session-Token")String token,@PathVariable String userId,@PathVariable String id,@RequestBody Mistake item)throws Exception{admin(token);return mistakes.update(userId,id,item);}
    @DeleteMapping("/mistakes/{userId}/{id}") public Map<String,Object> deleteMistake(@RequestHeader("X-Session-Token")String token,@PathVariable String userId,@PathVariable String id)throws Exception{admin(token);mistakes.delete(userId,id);return ok("错题已删除");}

    @GetMapping("/stats") public Map<String,Object> stats(@RequestHeader("X-Session-Token")String token)throws Exception{admin(token);return stats.admin();}
    @GetMapping("/stats/{userId}") public Map<String,Object> userStats(@RequestHeader("X-Session-Token")String token,@PathVariable String userId)throws Exception{admin(token);return stats.personal(userId);}
    @GetMapping("/online") public List<OnlineState> online(@RequestHeader("X-Session-Token")String token)throws Exception{admin(token);return usage.onlineUsers();}
    @GetMapping("/report/preview") public Map<String,Object> preview(@RequestHeader("X-Session-Token")String token)throws Exception{admin(token);Map<String,Object> result=new java.util.LinkedHashMap<>();result.put("content",reports.preview());return result;}
    @PostMapping("/report/send") public Map<String,Object> send(@RequestHeader("X-Session-Token")String token)throws Exception{admin(token);return reports.send(true);}
    private Map<String,Object> ok(String message){return java.util.Collections.<String,Object>singletonMap("message",message);}
    public static class UserRequest{public String username;public String name;public String password;public String role;public List<String> permissions=new ArrayList<>();}
    public static class InviteRequest{public String note;public int maxUses=1;public int validDays=7;}
}
