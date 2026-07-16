# Nginx 配置

普通服务器直接运行根目录的 `install.sh`，不需要手工复制这里的模板。

如果 443 已被 Xray Reality 使用，可采用 SNI 分流：Xray 改为仅监听
`127.0.0.1:8443`，网站 HTTPS 监听 `127.0.0.1:8444`，stream 层统一监听
公网 443。先备份 x-ui 数据库和 Nginx 配置，并分别执行 `xray -test`、
`nginx -t` 后再切换。

- `site.conf.example`：HTTP 证书验证、HTTPS 网站与 Java 反向代理。
- `xray-sni-stream.conf.example`：网站域名进入 Nginx，其他 SNI 进入 Reality。

Java 始终只监听 `127.0.0.1:8088`，无需在防火墙开放 8088。
