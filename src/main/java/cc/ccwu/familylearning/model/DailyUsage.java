package cc.ccwu.familylearning.model;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class DailyUsage {
    public String date;
    public int loginCount;
    public int newUsers;
    public int peakOnline;
    public long totalSeconds;
    public int frontendErrors;
    public int backendErrors;
    public Set<String> activeUserIds = new LinkedHashSet<>();
    public Map<String, Integer> userLogins = new LinkedHashMap<>();
    public Map<String, Long> userSeconds = new LinkedHashMap<>();
    public Map<String, Long> pageSeconds = new LinkedHashMap<>();
    public Map<String, Long> featureSeconds = new LinkedHashMap<>();
    public Map<String, Integer> pageViews = new LinkedHashMap<>();
    public Map<String, Integer> featureStarts = new LinkedHashMap<>();
    public Map<String, Integer> devices = new LinkedHashMap<>();
}
