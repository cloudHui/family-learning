package cc.ccwu.familylearning.service;

import cc.ccwu.familylearning.model.DailyUsage;
import cc.ccwu.familylearning.model.LearningRecord;
import cc.ccwu.familylearning.model.Mistake;
import cc.ccwu.familylearning.model.Student;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class StatsService {
    private static final Logger log = LoggerFactory.getLogger(StatsService.class);
    private final RecordService records;
    private final MistakeService mistakes;
    private final StudentService students;
    private final UsageService usage;
    private final WordService words;

    public StatsService(RecordService records, MistakeService mistakes, StudentService students,
                        UsageService usage, WordService words) {
        this.records = records; this.mistakes = mistakes; this.students = students; this.usage = usage; this.words = words;
    }

    public Map<String, Object> personal(String userId) throws Exception {
        List<LearningRecord> allRecords = records.list(userId);
        List<Mistake> allMistakes = mistakes.list(userId, null, null);
        LocalDate today = LocalDate.now();
        Student student = students.get(userId); String currentStage = student == null ? "幼小衔接" : safe(student.stage, "幼小衔接");
        Map<String, Object> result = new LinkedHashMap<>();

        int todaySessions = 0, todayTotal = 0, todayCorrect = 0, newMistakes = 0;
        long todaySeconds = usage.today().userSeconds.getOrDefault(userId, 0L);
        long recordedTodaySeconds = 0;
        Set<LocalDate> studyDays = new HashSet<>();
        Map<String, long[]> subjects = new LinkedHashMap<>();
        Map<LocalDate, long[]> dailyRecords = new HashMap<>();
        int mathTotal = 0, mathCorrect = 0; long mathSeconds = 0;
        Map<String, int[]> mathRanges = new LinkedHashMap<>();
        Map<String, Boolean> latestWords = new LinkedHashMap<>();
        Set<String> practicedWords = new HashSet<>();
        Set<String> stagePracticedWords = new HashSet<>();

        for (LearningRecord record : allRecords) {
            if (record.createdAt != null) {
                LocalDate recordDate = record.createdAt.toLocalDate(); studyDays.add(recordDate);
                long[] day = dailyRecords.computeIfAbsent(recordDate, key -> new long[3]);
                day[0] += record.total; day[1] += record.correct; day[2] += record.durationSeconds;
            }
            if (record.createdAt != null && today.equals(record.createdAt.toLocalDate())) {
                todaySessions++; todayTotal += record.total; todayCorrect += record.correct; recordedTodaySeconds += record.durationSeconds;
            }
            String subject = safe(record.subject, "其他"); long[] subjectData = subjects.computeIfAbsent(subject, key -> new long[4]);
            subjectData[0]++; subjectData[1] += record.durationSeconds; subjectData[2] += record.total; subjectData[3] += record.correct;
            if ("数学".equals(record.subject)) {
                mathTotal += record.total; mathCorrect += record.correct; mathSeconds += record.durationSeconds;
                int[] range = mathRanges.computeIfAbsent(safe(record.module, "其他"), key -> new int[2]); range[0] += record.total; range[1] += record.correct;
            }
            if ("语文".equals(record.subject) && record.details != null) {
                Object character = record.details.get("character");
                if (character != null) {
                    String text = String.valueOf(character); practicedWords.add(text);
                    if (currentStage.equals(safe(record.stage, "幼小衔接"))) stagePracticedWords.add(text);
                    if (!latestWords.containsKey(text)) latestWords.put(text, record.correct >= record.total && record.total > 0);
                }
            }
        }
        for (Mistake item : allMistakes) if (item.firstWrongAt != null && today.equals(item.firstWrongAt.toLocalDate())) newMistakes++;

        if (todaySeconds == 0) todaySeconds = recordedTodaySeconds;
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("studySeconds", todaySeconds); overview.put("sessions", todaySessions); overview.put("completed", todayTotal);
        overview.put("accuracy", percent(todayCorrect, todayTotal)); overview.put("newMistakes", newMistakes);
        overview.put("pendingMistakes", allMistakes.stream().filter(item -> !"已掌握".equals(item.status)).count());
        overview.put("streakDays", streak(studyDays)); result.put("today", overview);

        List<Map<String, Object>> trends30 = trends(userId, dailyRecords, 30);
        result.put("trends7", new ArrayList<>(trends30.subList(Math.max(0, trends30.size() - 7), trends30.size())));
        result.put("trends30", trends30);
        Map<String, Object> chinese = new LinkedHashMap<>();
        chinese.put("learned", practicedWords.size()); chinese.put("known", latestWords.values().stream().filter(Boolean::booleanValue).count());
        chinese.put("fuzzy", latestWords.values().stream().filter(value -> !value).count()); chinese.put("unknown", Math.max(0, words.list(currentStage).size() - stagePracticedWords.size()));
        chinese.put("dictationAccuracy", moduleAccuracy(allRecords, "语文", "听写")); chinese.put("frequentWrong", frequent(allMistakes, "语文", 8)); result.put("chinese", chinese);

        Map<String, Object> math = new LinkedHashMap<>(); math.put("total", mathTotal); math.put("accuracy", percent(mathCorrect, mathTotal));
        math.put("averageSeconds", mathTotal == 0 ? 0 : Math.round(mathSeconds * 10.0 / mathTotal) / 10.0); math.put("ranges", rangeViews(mathRanges));
        math.put("frequentWrong", frequent(allMistakes, "数学", 8)); result.put("math", math);

        Map<String, Integer> mistakeStatus = new LinkedHashMap<>();
        for (String status : new String[]{"待复习", "复习中", "基本掌握", "已掌握"}) mistakeStatus.put(status, 0);
        Map<String, Integer> mistakeSubjects = new LinkedHashMap<>();
        for (Mistake item : allMistakes) { mistakeStatus.put(item.status, mistakeStatus.getOrDefault(item.status, 0) + 1); mistakeSubjects.put(item.subject, mistakeSubjects.getOrDefault(item.subject, 0) + 1); }
        Map<String, Object> mistakeView = new LinkedHashMap<>(); mistakeView.put("status", mistakeStatus); mistakeView.put("subjects", mistakeSubjects); mistakeView.put("repeated", frequent(allMistakes, null, 10)); result.put("mistakes", mistakeView);
        result.put("subjects", subjectViews(subjects));
        Map<String, Object> progress = new LinkedHashMap<>(); int totalWords = words.list(currentStage).size();
        progress.put("stage", currentStage); progress.put("completed", stagePracticedWords.size()); progress.put("total", totalWords); progress.put("percent", percent(stagePracticedWords.size(), totalWords)); result.put("stageProgress", progress);
        Map<String, Object> habits = new LinkedHashMap<>(); habits.put("streakDays", streak(studyDays)); habits.put("lastStudyDate", studyDays.stream().max(LocalDate::compareTo).orElse(null));
        habits.put("daysThisWeek", studyDays.stream().filter(day -> !day.isBefore(today.minusDays(6))).count()); habits.put("goalDays", 5); result.put("habits", habits);
        return result;
    }

    public Map<String, Object> admin() throws Exception {
        DailyUsage today = usage.today(); List<Student> users = students.list(); Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalUsers", users.size()); result.put("activeUsers", today.activeUserIds.size()); result.put("newUsers", today.newUsers);
        result.put("loginCount", today.loginCount); result.put("online", usage.onlineUsers().size()); result.put("peakOnline", today.peakOnline);
        result.put("totalSeconds", today.totalSeconds); result.put("pageViews", sortMap(today.pageViews)); result.put("pageSeconds", sortMap(today.pageSeconds));
        result.put("featureStarts", sortMap(today.featureStarts)); result.put("featureSeconds", sortMap(today.featureSeconds)); result.put("devices", today.devices);
        result.put("frontendErrors", today.frontendErrors); result.put("backendErrors", today.backendErrors);
        int completed = 0, correct = 0, newMistakes = 0; List<Mistake> combined = new ArrayList<>();
        for (Student user : users) {
            try {
                for (LearningRecord record : records.list(user.id)) if (record.createdAt != null && LocalDate.now().equals(record.createdAt.toLocalDate())) { completed += record.total; correct += record.correct; }
                for (Mistake item : mistakes.list(user.id, null, null)) { combined.add(item); if (item.firstWrongAt != null && LocalDate.now().equals(item.firstWrongAt.toLocalDate())) newMistakes++; }
            } catch (Exception exception) {
                log.error("管理员统计跳过异常用户: {}", user.id, exception);
            }
        }
        result.put("completed", completed); result.put("accuracy", percent(correct, completed)); result.put("newMistakes", newMistakes);
        result.put("frequentErrors", frequent(combined, null, 15)); result.put("onlineUsers", usage.onlineUsers()); return result;
    }

    private List<Map<String, Object>> trends(String userId, Map<LocalDate, long[]> dailyRecords, int days) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>(); LocalDate today = LocalDate.now();
        for (int offset = days - 1; offset >= 0; offset--) {
            LocalDate date = today.minusDays(offset); long[] recorded = dailyRecords.getOrDefault(date, new long[3]);
            int total = (int) recorded[0], correct = (int) recorded[1]; long seconds = usage.day(date).userSeconds.getOrDefault(userId, 0L);
            if (seconds == 0) seconds = recorded[2];
            Map<String, Object> item = new LinkedHashMap<>(); item.put("date", date); item.put("seconds", seconds); item.put("completed", total); item.put("accuracy", percent(correct, total)); result.add(item);
        }
        return result;
    }
    private double moduleAccuracy(List<LearningRecord> records, String subject, String module) { int total = 0, correct = 0; for (LearningRecord item : records) if (subject.equals(item.subject) && module.equals(item.module)) { total += item.total; correct += item.correct; } return percent(correct, total); }
    private List<Map<String, Object>> frequent(List<Mistake> items, String subject, int limit) {
        Map<String, int[]> counts = new HashMap<>(); for (Mistake item : items) if (subject == null || subject.equals(item.subject)) { int[] value = counts.computeIfAbsent(safe(item.question, "未知问题"), key -> new int[2]); value[0] += Math.max(1, item.errorCount); value[1]++; }
        List<Map<String, Object>> result = new ArrayList<>(); counts.entrySet().stream().sorted((a,b) -> Integer.compare(b.getValue()[0], a.getValue()[0])).limit(limit).forEach(entry -> { Map<String,Object> row=new LinkedHashMap<>(); row.put("question",entry.getKey()); row.put("errors",entry.getValue()[0]); row.put("users",entry.getValue()[1]); result.add(row); }); return result;
    }
    private List<Map<String, Object>> rangeViews(Map<String, int[]> ranges) { List<Map<String,Object>> result=new ArrayList<>(); ranges.forEach((name,data)->{Map<String,Object> item=new LinkedHashMap<>();item.put("name",name);item.put("total",data[0]);item.put("accuracy",percent(data[1],data[0]));result.add(item);}); return result; }
    private List<Map<String, Object>> subjectViews(Map<String, long[]> subjects) { List<Map<String,Object>> result=new ArrayList<>(); subjects.forEach((name,data)->{Map<String,Object> item=new LinkedHashMap<>();item.put("subject",name);item.put("sessions",data[0]);item.put("seconds",data[1]);item.put("completed",data[2]);item.put("accuracy",percent((int)data[3],(int)data[2]));result.add(item);}); return result; }
    private Map<String, ? extends Number> sortMap(Map<String, ? extends Number> source) { Map<String, Number> result=new LinkedHashMap<>(); source.entrySet().stream().sorted((a,b)->Double.compare(b.getValue().doubleValue(),a.getValue().doubleValue())).forEach(entry->result.put(entry.getKey(),entry.getValue())); return result; }
    private int streak(Set<LocalDate> days) { int count=0; LocalDate day=LocalDate.now(); if(!days.contains(day)) day=day.minusDays(1); while(days.contains(day)){count++;day=day.minusDays(1);} return count; }
    private double percent(int correct, int total) { return total == 0 ? 0 : Math.round(correct * 1000.0 / total) / 10.0; }
    private String safe(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value; }
}
