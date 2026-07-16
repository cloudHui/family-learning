package cc.ccwu.familylearning.service;

import cc.ccwu.familylearning.model.MathQuestion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MathServiceTest {
    private final MathService service = new MathService(null);

    @Test
    void generatesTenWithinRange() {
        List<MathQuestion> questions = service.generate(10, 10, "mixed");
        assertThat(questions).hasSize(10);
        assertThat(questions).allSatisfy(question -> {
            assertThat(question.answer).isBetween(0, 10);
            assertThat(question.text).isNotBlank();
        });
    }
}
