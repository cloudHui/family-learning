package cc.ccwu.familylearning.service;

import cc.ccwu.familylearning.model.DailyUsage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

@Service
public class DailyReportService {
    private final JavaMailSender mailSender;
    private final StatsService stats;
    private final UsageService usage;
    private final JsonFileStore store;
    private final String recipient;
    private final String sender;

    public DailyReportService(ObjectProvider<JavaMailSender> mailSender, StatsService stats, UsageService usage, JsonFileStore store,
                              @Value("${family-learning.report.recipient:}") String recipient,
                              @Value("${spring.mail.username:}") String sender) {
        this.mailSender = mailSender.getIfAvailable(); this.stats = stats; this.usage = usage; this.store = store;
        this.recipient = recipient; this.sender = sender;
    }

    @Scheduled(cron = "${family-learning.report.cron}", zone = "${family-learning.report.zone}")
    public void scheduled() { try { send(false); } catch (Exception exception) { log("发送失败: " + exception.getMessage()); } }

    public synchronized Map<String, Object> send(boolean force) throws Exception {
        DailyUsage today = usage.today();
        if (!force && today.loginCount == 0) return status("skipped", "今日没有用户登录，不发送邮件");
        if (today.loginCount == 0) return status("skipped", "今日没有用户登录");
        if (mailSender == null || recipient == null || recipient.trim().isEmpty() || sender == null || sender.trim().isEmpty())
            return status("disabled", "邮件配置尚未完成");
        Map<String, Object> data = stats.admin();
        SimpleMailMessage message = new SimpleMailMessage(); message.setFrom(sender); message.setTo(recipient);
        message.setSubject("[成长小课堂日报] " + LocalDate.now()); message.setText(render(data)); mailSender.send(message);
        log("发送成功: " + recipient); return status("sent", "日报已发送到 " + recipient);
    }

    public String preview() throws Exception { return render(stats.admin()); }

    private String render(Map<String, Object> data) {
        StringBuilder text = new StringBuilder();
        text.append("成长小课堂每日统计\n").append("日期：").append(LocalDate.now()).append("\n\n");
        text.append("用户使用\n");
        text.append("- 今日活跃用户：").append(data.get("activeUsers")).append("\n");
        text.append("- 今日新增用户：").append(data.get("newUsers")).append("\n");
        text.append("- 登录次数：").append(data.get("loginCount")).append("\n");
        text.append("- 最高同时在线：").append(data.get("peakOnline")).append("\n");
        text.append("- 总使用时长：").append(data.get("totalSeconds")).append(" 秒\n\n");
        text.append("学习汇总\n");
        text.append("- 完成题/字：").append(data.get("completed")).append("\n");
        text.append("- 正确率：").append(data.get("accuracy")).append("%\n");
        text.append("- 新增错题：").append(data.get("newMistakes")).append("\n\n");
        text.append("页面访问排行：").append(data.get("pageViews")).append("\n");
        text.append("功能使用排行：").append(data.get("featureStarts")).append("\n");
        text.append("高频错误：").append(data.get("frequentErrors")).append("\n\n");
        text.append("前端错误：").append(data.get("frontendErrors")).append("，后端错误：").append(data.get("backendErrors")).append("\n");
        return text.toString();
    }
    private Map<String, Object> status(String status, String message) { Map<String,Object> result=new java.util.LinkedHashMap<>();result.put("status",status);result.put("message",message);return result; }
    private void log(String message) { try { java.nio.file.Files.write(store.root().resolve("reports/mail.log"), (LocalDateTime.now()+" "+message+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); } catch (Exception ignored) {} }
}
