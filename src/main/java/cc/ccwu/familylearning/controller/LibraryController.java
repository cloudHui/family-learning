package cc.ccwu.familylearning.controller;

import cc.ccwu.familylearning.service.AuthService;
import cc.ccwu.familylearning.service.LibraryService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 开放学习库接口。
 * 列表类（图卡/词汇/词典/诗词/汉字）统一翻页；汉字笔顺详情单独按字读取。
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

    /**
     * 汉字列表翻页，或按 value 取笔顺详情。
     * 列表统一：query 搜索 + tag 标签；value 有值时返回笔顺详情 JSON。
     */
    @GetMapping("/character")
    public Object character(@RequestHeader(value = "X-Session-Token", required = false) String token,
                            @RequestParam(required = false) String value,
                            @RequestParam(defaultValue = "") String query,
                            @RequestParam(defaultValue = "") String tag,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "48") int size) throws Exception {
        auth.requirePermission(token, "CHINESE");
        if (value != null && !value.trim().isEmpty()) {
            return library.character(value.trim());
        }
        return library.characterPage(query, tag, page, size);
    }

    /** 英汉词典翻页：统一 query + tag。 */
    @GetMapping("/dictionary")
    public Map<String, Object> dictionary(@RequestHeader(value = "X-Session-Token", required = false) String token,
                                          @RequestParam(defaultValue = "") String query,
                                          @RequestParam(defaultValue = "") String tag,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "30") int size) throws Exception {
        auth.requirePermission(token, "ENGLISH");
        return library.dictionaryPage(query, tag, page, size);
    }

    /** 古诗词翻页：统一 query + tag。 */
    @GetMapping("/poetry")
    public Map<String, Object> poetry(@RequestHeader(value = "X-Session-Token", required = false) String token,
                                      @RequestParam(defaultValue = "") String query,
                                      @RequestParam(defaultValue = "") String tag,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size) throws Exception {
        auth.requirePermission(token, "CHINESE");
        return library.poetryPage(query, tag, page, size);
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

    /** 儿童英语图卡翻页。 */
    @GetMapping("/english")
    public Map<String, Object> english(@RequestHeader(value = "X-Session-Token", required = false) String token,
                                       @RequestParam(defaultValue = "") String query,
                                       @RequestParam(defaultValue = "") String tag,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "24") int size) throws Exception {
        auth.requirePermission(token, "ENGLISH");
        return library.englishKidsPage(query, tag, page, size);
    }

    /** 常用英语词汇翻页。 */
    @GetMapping("/vocab")
    public Map<String, Object> vocab(@RequestHeader(value = "X-Session-Token", required = false) String token,
                                     @RequestParam(defaultValue = "") String query,
                                     @RequestParam(defaultValue = "") String tag,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "30") int size) throws Exception {
        auth.requirePermission(token, "ENGLISH");
        return library.englishVocabPage(query, tag, page, size);
    }
}
