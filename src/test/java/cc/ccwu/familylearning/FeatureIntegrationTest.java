package cc.ccwu.familylearning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class FeatureIntegrationTest {
    private static final Path ROOT = java.nio.file.Paths.get("/tmp/family-learning-test-" + UUID.randomUUID());
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("family-learning.data-dir", () -> ROOT.resolve("data").toString());
        registry.add("family-learning.resource-dir", () -> ROOT.resolve("resources").toString());
        registry.add("family-learning.report.recipient", () -> "");
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void completeUserAdminStatisticsAndPrintFlow() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().exists("Content-Security-Policy"));
        JsonNode adminLogin = login("admin", "123456"); String admin = adminLogin.get("token").asText();
        org.assertj.core.api.Assertions.assertThat(adminLogin.get("user").get("mustChangePassword").asBoolean()).isTrue();
        changePassword(admin,"123456","admin789");
        JsonNode userLogin = login("testkid", "123456"); String token = userLogin.get("token").asText();
        String userId = userLogin.get("user").get("id").asText();

        mvc.perform(get("/api/auth/me").header("X-Session-Token", token)).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("testkid"));
        mvc.perform(get("/api/words").header("X-Session-Token", token)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("请先修改初始密码"));
        changePassword(token,"123456","kidpass");
        mvc.perform(get("/api/admin/users").header("X-Session-Token", token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/words").header("X-Session-Token", token)).andExpect(status().isOk()).andExpect(jsonPath("$[0].character").exists());
        mvc.perform(get("/api/math/questions?max=10&count=5").header("X-Session-Token", token)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5));
        mvc.perform(get("/api/math/printable?max=10&count=5&wordProblems=2&stage=幼小衔接").header("X-Session-Token", token)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(7));

        String record = "{\"subject\":\"数学\",\"module\":\"10以内算术\",\"stage\":\"幼小衔接\",\"total\":5,\"correct\":4,\"durationSeconds\":30}";
        JsonNode recordNode = json(mvc.perform(post("/api/records").header("X-Session-Token",token).contentType("application/json").content(record)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String mistake = "{\"subject\":\"数学\",\"module\":\"10以内算术\",\"question\":\"3 + 4 = ?\",\"userAnswer\":\"6\",\"correctAnswer\":\"7\"}";
        JsonNode mistakeNode = json(mvc.perform(post("/api/mistakes").header("X-Session-Token",token).contentType("application/json").content(mistake)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(post("/api/mistakes/"+mistakeNode.get("id").asText()+"/review").header("X-Session-Token",token).contentType("application/json").content("{\"correct\":true}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("复习中"));
        mvc.perform(post("/api/auth/heartbeat").header("X-Session-Token",token).contentType("application/json").content("{\"page\":\"数学区\",\"feature\":\"算术答题\",\"device\":\"电脑\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/stats").header("X-Session-Token",token)).andExpect(status().isOk()).andExpect(jsonPath("$.today.completed").value(5)).andExpect(jsonPath("$.math.total").value(5));

        JsonNode users = json(mvc.perform(get("/api/admin/users").header("X-Session-Token",admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(users.size()).isGreaterThanOrEqualTo(2);
        mvc.perform(get("/api/admin/online").header("X-Session-Token",admin)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/stats").header("X-Session-Token",admin)).andExpect(status().isOk()).andExpect(jsonPath("$.activeUsers").value(2));
        mvc.perform(get("/api/admin/stats/"+userId).header("X-Session-Token",admin)).andExpect(status().isOk()).andExpect(jsonPath("$.today.completed").value(5));

        String managed="{\"username\":\"managed\",\"name\":\"权限测试\",\"role\":\"USER\",\"permissions\":[\"CHINESE\"]}";
        JsonNode managedNode=json(mvc.perform(post("/api/admin/users").header("X-Session-Token",admin).contentType("application/json").content(managed)).andExpect(status().isOk()).andExpect(jsonPath("$.permissions.length()").value(1)).andReturn().getResponse().getContentAsString());
        String managedId=managedNode.get("id").asText(); String managedToken=login("managed","123456").get("token").asText();
        changePassword(managedToken,"123456","managed789");
        mvc.perform(get("/api/math/questions").header("X-Session-Token",managedToken)).andExpect(status().isUnauthorized());
        String grant="{\"name\":\"权限测试\",\"role\":\"USER\",\"stage\":\"一年级\",\"enabled\":true,\"permissions\":[\"CHINESE\",\"MATH\"]}";
        mvc.perform(put("/api/admin/users/"+managedId).header("X-Session-Token",admin).contentType("application/json").content(grant)).andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("一年级"));
        mvc.perform(get("/api/math/questions?count=1").header("X-Session-Token",managedToken)).andExpect(status().isOk());
        mvc.perform(delete("/api/admin/users/"+managedId).header("X-Session-Token",admin)).andExpect(status().isOk());

        String word = "{\"stage\":\"幼小衔接\",\"character\":\"学\",\"pinyin\":\"xué\",\"words\":\"学习\"}";
        JsonNode wordNode=json(mvc.perform(post("/api/admin/words").header("X-Session-Token",admin).contentType("application/json").content(word)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(delete("/api/admin/words/"+wordNode.get("id").asText()).header("X-Session-Token",admin)).andExpect(status().isOk());

        String content="{\"subject\":\"英语\",\"stage\":\"一年级\",\"type\":\"知识卡片\",\"title\":\"Hello\",\"body\":\"你好\",\"enabled\":true}";
        JsonNode contentNode=json(mvc.perform(post("/api/admin/content").header("X-Session-Token",admin).contentType("application/json").content(content)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(delete("/api/admin/content/"+contentNode.get("id").asText()).header("X-Session-Token",admin)).andExpect(status().isOk());
        String template="{\"stage\":\"幼小衔接\",\"operation\":\"add\",\"maxNumber\":10,\"template\":\"有{a}个，又来{b}个，共多少个？\",\"enabled\":true}";
        JsonNode templateNode=json(mvc.perform(post("/api/admin/templates").header("X-Session-Token",admin).contentType("application/json").content(template)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(delete("/api/admin/templates/"+templateNode.get("id").asText()).header("X-Session-Token",admin)).andExpect(status().isOk());

        mvc.perform(put("/api/admin/records/"+userId+"/"+recordNode.get("id").asText()).header("X-Session-Token",admin).contentType("application/json").content(record)).andExpect(status().isOk());
        mvc.perform(put("/api/admin/mistakes/"+userId+"/"+mistakeNode.get("id").asText()).header("X-Session-Token",admin).contentType("application/json").content(mistake)).andExpect(status().isOk());
        JsonNode adminRecord=json(mvc.perform(post("/api/admin/records/"+userId).header("X-Session-Token",admin).contentType("application/json").content("{\"subject\":\"语文\",\"module\":\"管理员补录\",\"total\":1,\"correct\":1}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode adminMistake=json(mvc.perform(post("/api/admin/mistakes/"+userId).header("X-Session-Token",admin).contentType("application/json").content("{\"subject\":\"语文\",\"module\":\"管理员补录\",\"question\":\"天\",\"correctAnswer\":\"tiān\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(delete("/api/admin/records/"+userId+"/"+adminRecord.get("id").asText()).header("X-Session-Token",admin)).andExpect(status().isOk());
        mvc.perform(delete("/api/admin/mistakes/"+userId+"/"+adminMistake.get("id").asText()).header("X-Session-Token",admin)).andExpect(status().isOk());
        mvc.perform(delete("/api/admin/records/"+userId+"/"+recordNode.get("id").asText()).header("X-Session-Token",admin)).andExpect(status().isOk());
        mvc.perform(delete("/api/admin/mistakes/"+userId+"/"+mistakeNode.get("id").asText()).header("X-Session-Token",admin)).andExpect(status().isOk());

        MockMultipartFile file=new MockMultipartFile("file","exercise.txt","text/plain","hello".getBytes("UTF-8"));
        mvc.perform(MockMvcRequestBuilders.multipart("/api/resources").file(file).param("subject","worksheets").header("X-Session-Token",admin)).andExpect(status().isOk());
        mvc.perform(delete("/api/resources").param("path","worksheets/exercise.txt").header("X-Session-Token",admin)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/report/preview").header("X-Session-Token",admin)).andExpect(status().isOk()).andExpect(jsonPath("$.content").exists());
        mvc.perform(post("/api/admin/report/send").header("X-Session-Token",admin)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("disabled"));

        changePassword(token,"kidpass","abcdef");
        mvc.perform(post("/api/admin/users/"+userId+"/reset-password").header("X-Session-Token",admin)).andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(login("testkid","123456").get("user").get("mustChangePassword").asBoolean()).isTrue();
    }

    private JsonNode login(String username,String password)throws Exception{
        String body="{\"username\":\""+username+"\",\"password\":\""+password+"\",\"device\":\"测试\"}";
        return json(mvc.perform(post("/api/auth/login").contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private void changePassword(String token,String oldPassword,String newPassword)throws Exception{
        String body="{\"oldPassword\":\""+oldPassword+"\",\"newPassword\":\""+newPassword+"\"}";
        mvc.perform(post("/api/auth/password").header("X-Session-Token",token).contentType("application/json").content(body)).andExpect(status().isOk());
    }
    private JsonNode json(String value)throws Exception{return mapper.readTree(value);}
}
