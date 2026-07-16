package cc.ccwu.familylearning.service;

import cc.ccwu.familylearning.model.MathQuestion;
import cc.ccwu.familylearning.model.PrintableQuestion;
import cc.ccwu.familylearning.model.WordProblemTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

@Service
public class MathService {
    private final Random random = new Random();
    private final ContentService content;

    public MathService(ContentService content) { this.content = content; }

    public List<MathQuestion> generate(int max, int count, String operation) {
        max = Math.max(5, Math.min(max, 1000));
        count = Math.max(1, Math.min(count, 100));
        List<MathQuestion> questions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            boolean add = "add".equals(operation) || (!"sub".equals(operation) && random.nextBoolean());
            int left = random.nextInt(max + 1);
            int right = random.nextInt(max + 1);
            if (add) {
                while (left + right > max) { left = random.nextInt(max + 1); right = random.nextInt(max + 1); }
                questions.add(new MathQuestion(UUID.randomUUID().toString(), left, right, "+", left + right));
            } else {
                if (right > left) { int swap = left; left = right; right = swap; }
                questions.add(new MathQuestion(UUID.randomUUID().toString(), left, right, "−", left - right));
            }
        }
        return questions;
    }

    public List<PrintableQuestion> printable(int max, int count, String operation, int wordProblemCount, String stage) throws Exception {
        List<PrintableQuestion> result = new ArrayList<>();
        if (count > 0) for (MathQuestion question : generate(max, count, operation))
            result.add(new PrintableQuestion(question.id, "算术", question.text.replace("?", "______"), String.valueOf(question.answer)));
        List<WordProblemTemplate> available = new ArrayList<>();
        for (WordProblemTemplate item : content.templates())
            if (item.enabled && (stage == null || stage.isEmpty() || stage.equals(item.stage)) && item.maxNumber <= max) available.add(item);
        if (available.isEmpty()) available = content.templates();
        for (int i = 0; i < Math.max(0, Math.min(50, wordProblemCount)) && !available.isEmpty(); i++) {
            WordProblemTemplate template = available.get(random.nextInt(available.size()));
            boolean add = "add".equals(template.operation); int a = random.nextInt(max + 1); int b = random.nextInt(max + 1);
            if (add) while (a + b > max) { a = random.nextInt(max + 1); b = random.nextInt(max + 1); }
            else if (b > a) { int swap = a; a = b; b = swap; }
            String text = template.template.replace("{a}", String.valueOf(a)).replace("{b}", String.valueOf(b));
            result.add(new PrintableQuestion(UUID.randomUUID().toString(), "文字题", text, String.valueOf(add ? a + b : a - b)));
        }
        return result;
    }
}
