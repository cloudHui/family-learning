package cc.ccwu.familylearning.controller;

import cc.ccwu.familylearning.service.AuthService;
import cc.ccwu.familylearning.service.LibraryService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 开放学习库接口：教材、汉字、词典、古诗词、儿童英语。
 * 权限与首页学科权限对齐，避免无权限时静默失败难排查。
 */
@RestController
@RequestMapping("/api/library")
public class LibraryController {
    private final AuthService auth;
    private final LibraryService library;

    public LibraryController(AuthService auth, LibraryService library) {
        this.auth = auth;
        this.library = library;
    }

    /** 数据就绪状态。 */
    @GetMapping("/status")
    public Map<String, Object> status(@RequestHeader(value = "X-Session-Token", required = false) String token) throws Exception {
        auth.requirePermission(token, "RESOURCES");
        return library.status();
    }

    /** 查询单个汉字笔顺。 */
    @GetMapping("/character")
    public JsonNode character(@RequestHeader(value = "X-Session-Token", required = false) String token,
                              @RequestParam String value) throws Exception {
        auth.requirePermission(token, "CHINESE");
        return library.character(value);
    }

    /** 查询英汉词典词条。 */
    @GetMapping("/dictionary")
    public List<JsonNode> dictionary(@RequestHeader(value = "X-Session-Token", required = false) String token,
                                     @RequestParam String query) throws Exception {
        auth.requirePermission(token, "ENGLISH");
        return library.dictionary(query);
    }

    /** 查询古诗词（本地索引）。 */
    @GetMapping("/poetry")
    public List<JsonNode> poetry(@RequestHeader(value = "X-Session-Token", required = false) String token,
                                 @RequestParam(defaultValue = "") String query) throws Exception {
        auth.requirePermission(token, "CHINESE");
        return library.poetry(query);
    }

    /** 查询教材目录（仅链接，不下载 PDF）。 */
    @GetMapping("/textbooks")
    public List<JsonNode> textbooks(@RequestHeader(value = "X-Session-Token", required = false) String token,
                                    @RequestParam(defaultValue = "") String query) throws Exception {
        auth.requirePermission(token, "RESOURCES");
        return library.textbooks(query);
    }

    /** 教材目录树浏览。 */
    @GetMapping("/textbooks/tree")
    public Map<String, Object> textbooksTree(@RequestHeader(value = "X-Session-Token", required = false) String token,
                                             @RequestParam(defaultValue = "") String prefix,
                                             @RequestParam(defaultValue = "") String query) throws Exception {
        auth.requirePermission(token, "RESOURCES");
        return library.textbooksTree(prefix, query);
    }

    /** 儿童英语图卡（图片+音频）。 */
    @GetMapping("/english")
    public List<Map<String, Object>> english(@RequestHeader(value = "X-Session-Token", required = false) String token,
                                             @RequestParam(defaultValue = "") String query) throws Exception {
        auth.requirePermission(token, "ENGLISH");
        return library.englishKids(query);
    }
}
