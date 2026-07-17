# 成长小课堂

轻量家庭学习工具：Java 8 + Spring Boot 2.7.18 + Vue 3 + JSON 文件存储，适合低配置 Linux 服务器。

## 主要功能

- 用户名/密码登录，新用户使用 `123456` 首次建档并强制修改密码
- 管理员管理用户、权限、密码、教学内容、记录、错题和资源
- 分阶段识字、浏览器语音、听写、Canvas 田字格手写
- 可配置加减法、自动判题、分阶段错题复习
- 随机算术题与文字题生成、答案和打印
- 个人今日/周/月、分科、错题、阶段和学习习惯统计
- 全局登录、页面、时长、功能、在线用户和高频错误统计
- 当天有人登录才发送每日统计邮件
- Nginx HTTPS 反向代理，Java 仅监听 `127.0.0.1:8088`

## 一键安装

支持 Debian、Ubuntu、CentOS、Rocky Linux 等使用 apt/dnf/yum 和 systemd 的发行版。

```bash
curl -fsSL https://raw.githubusercontent.com/cloudHui/family-learning/main/install.sh -o /tmp/family-learning-install.sh && sudo sh /tmp/family-learning-install.sh install
```

脚本会检查架构、磁盘、Git、curl、JDK、Maven、Nginx 和 systemd；缺少的软件自动安装，然后运行测试、打包、安装服务和健康检查。

安装时可以选择公网 IP、域名、访问唯一码和 HTTPS。公网 IP 默认不配置，直接回车即可继续。无域名时使用：

```text
http://服务器IP/访问唯一码/
```

有域名并启用 HTTPS 时使用：

```text
https://你的域名/访问唯一码/
```

默认管理员为 `admin / 123456`。首次登录必须设置新密码；管理员重置用户密码后，该用户也必须再次改密。

## 管理命令

```bash
sudo family-learning start
sudo family-learning stop
sudo family-learning restart
family-learning status
family-learning logs
sudo family-learning update
sudo /opt/family-learning/source/install.sh uninstall
```

`update` 会用 GitHub `main` 覆盖服务器上的程序源码和部署文件，重新测试构建；`/etc/family-learning/family-learning.env`、证书和学习数据保留不变。

更新前会运行测试并保留上一版 JAR，健康检查失败自动回滚。卸载默认保留学习数据。

## 数据与配置

- `/etc/family-learning/family-learning.env`：端口、路径和可选邮件配置
- `/var/lib/family-learning/`：JSON 学习数据
- `/var/lib/family-learning/resources/`：学习资源
- `/var/lib/family-learning/datasets/`：汉字、词典、诗词索引及教材链接缓存
- `/opt/family-learning/`：程序和源码

学习系统不依赖数据库。JSON 采用临时文件与原子替换写入；默认不执行自动备份。

本地构建：

```bash
./build.sh
```

## 端口与 Xray 共存

普通部署由 Nginx监听 80/443，Java 的 8088 不需要开放。若服务器的 443 已被 Xray Reality 使用，请参阅 [Nginx SNI 分流说明](deploy/nginx/README.md)，先备份 x-ui 数据再切换。

## 开源许可

[MIT](LICENSE)
