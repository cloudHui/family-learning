package cc.ccwu.familylearning.service;

import cc.ccwu.familylearning.model.DailyUsage;
import cc.ccwu.familylearning.model.OnlineState;
import cc.ccwu.familylearning.model.Student;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UsageService {
    private final JsonFileStore store;
    private final Map<String, OnlineState> online = new ConcurrentHashMap<>();
    private DailyUsage daily;

    public UsageService(JsonFileStore store) { this.store = store; }

    public synchronized void login(Student user, String device, boolean created) throws Exception {
        ensureDay();
        daily.loginCount++; if (created) daily.newUsers++;
        daily.activeUserIds.add(user.id); increment(daily.userLogins, user.id, 1);
        increment(daily.devices, clean(device, "未知设备"), 1);
        OnlineState state = new OnlineState(); state.userId = user.id; state.username = user.username; state.name = user.name;
        state.page = "登录"; state.feature = ""; state.device = clean(device, "未知设备"); state.loginAt = LocalDateTime.now(); state.lastSeenAt = state.loginAt;
        online.put(user.id, state); updatePeak(); flush();
    }

    public synchronized void heartbeat(Student user, String page, String feature, String device) throws Exception {
        ensureDay(); LocalDateTime now = LocalDateTime.now(); OnlineState previous = online.get(user.id);
        if (previous == null) {
            previous = new OnlineState(); previous.userId = user.id; previous.username = user.username; previous.name = user.name;
            previous.loginAt = now; previous.lastSeenAt = now; previous.page = clean(page, "首页"); previous.feature = clean(feature, ""); previous.device = clean(device, "未知设备");
            online.put(user.id, previous); daily.activeUserIds.add(user.id); increment(daily.pageViews, previous.page, 1);
        } else {
            long seconds = Math.min(30, Math.max(0, Duration.between(previous.lastSeenAt, now).getSeconds()));
            daily.totalSeconds += seconds; increment(daily.userSeconds, user.id, seconds);
            increment(daily.pageSeconds, clean(previous.page, "首页"), seconds);
            if (previous.feature != null && !previous.feature.isEmpty()) increment(daily.featureSeconds, previous.feature, seconds);
            String nextPage = clean(page, "首页"); String nextFeature = clean(feature, "");
            if (!nextPage.equals(previous.page)) increment(daily.pageViews, nextPage, 1);
            if (!nextFeature.isEmpty() && !nextFeature.equals(previous.feature)) increment(daily.featureStarts, nextFeature, 1);
            previous.page = nextPage; previous.feature = nextFeature; previous.device = clean(device, previous.device); previous.lastSeenAt = now;
        }
        updatePeak();
    }

    public synchronized void logout(String userId) { online.remove(userId); }
    public synchronized void frontendError() throws Exception { ensureDay(); daily.frontendErrors++; }
    public synchronized void backendError() { try { ensureDay(); daily.backendErrors++; } catch (Exception ignored) {} }

    public synchronized List<OnlineState> onlineUsers() {
        prune(); List<OnlineState> result = new ArrayList<>(online.values());
        result.sort(Comparator.comparing(state -> state.lastSeenAt, Comparator.reverseOrder())); return result;
    }

    public synchronized DailyUsage today() throws Exception { ensureDay(); return daily; }
    public synchronized DailyUsage day(LocalDate date) throws Exception {
        DailyUsage value = store.read(store.path("usage", date.toString()), DailyUsage.class);
        if (value == null) { value = new DailyUsage(); value.date = date.toString(); }
        return value;
    }

    @Scheduled(fixedDelay = 60000)
    public synchronized void scheduledFlush() { try { prune(); ensureDay(); flush(); } catch (Exception ignored) {} }
    @PreDestroy public synchronized void shutdown() { try { flush(); } catch (Exception ignored) {} }

    private void ensureDay() throws Exception {
        String today = LocalDate.now().toString();
        if (daily == null || !today.equals(daily.date)) {
            if (daily != null) flush();
            daily = store.read(store.path("usage", today), DailyUsage.class);
            if (daily == null) { daily = new DailyUsage(); daily.date = today; }
        }
    }
    private void flush() throws Exception { if (daily != null) store.write(store.path("usage", daily.date), daily); }
    private void prune() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(30);
        online.entrySet().removeIf(entry -> entry.getValue().lastSeenAt == null || entry.getValue().lastSeenAt.isBefore(cutoff));
    }
    private void updatePeak() { prune(); daily.peakOnline = Math.max(daily.peakOnline, online.size()); }
    private String clean(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value.trim().substring(0, Math.min(60, value.trim().length())); }
    private <K> void increment(Map<K, Integer> map, K key, int value) { map.put(key, map.getOrDefault(key, 0) + value); }
    private <K> void increment(Map<K, Long> map, K key, long value) { map.put(key, map.getOrDefault(key, 0L) + value); }
}
