package cc.ccwu.familylearning.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import cc.ccwu.familylearning.service.AuthService;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/resources")
public class ResourceController {
    private final Path root;
    private final AuthService auth;
    private static final List<String> ALLOWED = java.util.Arrays.asList(
            "pdf", "txt", "png", "jpg", "jpeg", "gif", "webp", "mp3", "wav", "ogg", "mp4");

    public ResourceController(@Value("${family-learning.resource-dir}") String resourceDir, AuthService auth) {
        this.root = Paths.get(resourceDir).toAbsolutePath().normalize();
        this.auth = auth;
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(root);
        for (String folder : java.util.Arrays.asList("chinese", "math", "english", "history", "chemistry", "picture-books", "poems", "worksheets")) {
            Files.createDirectories(root.resolve(folder));
        }
    }

    @GetMapping
    public List<ResourceInfo> list(@RequestHeader(value="X-Session-Token",required=false) String token,
                                   @RequestParam(required = false) String subject) throws Exception {
        auth.requirePermission(token,"RESOURCES");
        Path base = subject == null || subject.isEmpty() ? root : safePath(subject);
        if (!Files.isDirectory(base)) return new ArrayList<>();
        List<ResourceInfo> result = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(base, 4)) {
            paths.filter(Files::isRegularFile).filter(this::allowed).forEach(path -> {
                try { result.add(new ResourceInfo(root.relativize(path).toString().replace('\\', '/'), Files.size(path), Files.getLastModifiedTime(path).toMillis())); }
                catch (IOException ignored) {}
            });
        }
        result.sort(Comparator.comparing(item -> item.path));
        return result;
    }

    @GetMapping("/file")
    public ResponseEntity<FileSystemResource> file(@RequestHeader(value="X-Session-Token",required=false) String token,
                                                    @RequestParam String path,
                                                    @RequestParam(defaultValue = "false") boolean download) throws Exception {
        auth.requirePermission(token,"RESOURCES");
        Path file = safePath(path);
        if (!Files.isRegularFile(file) || !allowed(file)) throw new IllegalArgumentException("找不到资源文件");
        String type = Files.probeContentType(file);
        MediaType mediaType;
        try { mediaType = type == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(type); }
        catch (Exception ignored) { mediaType = MediaType.APPLICATION_OCTET_STREAM; }
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().contentType(mediaType);
        if (download) {
            String encoded = URLEncoder.encode(file.getFileName().toString(), StandardCharsets.UTF_8.name()).replace("+", "%20");
            response.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded);
        }
        return response.body(new FileSystemResource(file));
    }

    @PostMapping
    public ResourceInfo upload(@RequestHeader("X-Session-Token") String token,@RequestParam String subject,
                               @RequestPart("file") MultipartFile upload) throws Exception {
        auth.requireAdmin(token);
        if(upload.isEmpty()||upload.getSize()>50L*1024*1024)throw new IllegalArgumentException("文件为空或超过50MB");
        String name=Paths.get(upload.getOriginalFilename()==null?"resource":upload.getOriginalFilename()).getFileName().toString();
        Path target=safePath(subject).resolve(name).normalize();
        if(!target.startsWith(root)||!allowed(target))throw new IllegalArgumentException("不支持的文件类型");
        Files.createDirectories(target.getParent());upload.transferTo(target.toFile());
        return new ResourceInfo(root.relativize(target).toString().replace('\\','/'),Files.size(target),Files.getLastModifiedTime(target).toMillis());
    }

    @DeleteMapping
    public java.util.Map<String,Object> delete(@RequestHeader("X-Session-Token") String token,@RequestParam String path)throws Exception{
        auth.requireAdmin(token);Path file=safePath(path);if(!Files.deleteIfExists(file))throw new IllegalArgumentException("找不到资源文件");
        return java.util.Collections.<String,Object>singletonMap("message","资源已删除");
    }

    private Path safePath(String relative) {
        Path result = root.resolve(relative).normalize();
        if (!result.startsWith(root)) throw new IllegalArgumentException("非法资源路径");
        return result;
    }
    private boolean allowed(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && ALLOWED.contains(name.substring(dot + 1).toLowerCase());
    }

    public static class ResourceInfo {
        public String path;
        public long size;
        public long modifiedAt;
        public ResourceInfo(String path, long size, long modifiedAt) { this.path = path; this.size = size; this.modifiedAt = modifiedAt; }
    }
}
