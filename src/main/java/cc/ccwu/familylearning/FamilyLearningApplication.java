package cc.ccwu.familylearning;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FamilyLearningApplication {
    public static void main(String[] args) {
        SpringApplication.run(FamilyLearningApplication.class, args);
    }
}
