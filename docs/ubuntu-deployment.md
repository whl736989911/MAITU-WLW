# Ubuntu部署手册

## 唯一支持平台与前置条件

构建及部署仅支持 Ubuntu/Linux。目标运行环境为 Ubuntu 26.04 LTS；Java 21、PostgreSQL 17 和后端内嵌前端组成生产运行环境。构建需要 Maven 3.9.11 和 Node 24，自动验收需要 Python 3.12+。Ubuntu 默认仓库的 PostgreSQL 版本未必是 17，须按受维护渠道安装。

构建、迁移验证与生产上线分开。WSL 本地预览不能代替独立生产主机的内核、容量、网络或灾备验收。

## 1. 构建

```bash
bash scripts/prepare-tools.sh   # 可选，仅校验安装项目.tools/linux，不改系统PATH/服务
bash scripts/package.sh        # 已包含验证和打包
```

已有Java21/Node24/Maven可直接用Linux PATH/JAVA_HOME。打包失败即停，必须保留实际日志，不删测/跳test或用旧包顶替。复制新构建的`backend/target/fire-platform-backend-0.1.0-SNAPSHOT.jar`至版本化发布目录，核SHA后再切换稳定路径；不得复制其它系统的node_modules/target/dist。使用[根说明](<../README.md>)列出的当前Linux入口。

## 2. 数据库与私密配置

先建独立PG17数据库和最小权限应用角色，不与别的应用共用管理员身份。应用启动会执行Flyway，先对备份/隔离副本演练V13升级及真实恢复；禁止把第一次演练指向生产库。H2仅dev/test，不用于多进程共享或生产容量承诺。

管理员创建仅root可读的`/etc/fire-platform/platform.env`（例如mode0600），通过systemd的EnvironmentFile注入，不提交仓库、不把真实密码放命令参数/URL/日志。下面是模板，不含可用凭据：

```ini
APP_PROFILE=prod
APP_STORAGE=postgresql
APP_DB_URL=jdbc:postgresql://127.0.0.1:5432/fire_platform
APP_DB_USERNAME=fire_app
APP_DB_PASSWORD=<自行安全设置>
APP_BOOTSTRAP_USERNAME=admin
APP_BOOTSTRAP_PASSWORD=<首次初始化时安全设置至少12字符密码>
APP_BIND_ADDRESS=127.0.0.1
APP_PORT=8080
APP_ROLES=all
APP_SIMULATION_ENABLED=false
```

其它开关默认关闭，完整键及约束见[环境模板](<../.env.example>)。初始账户不存在时才初始化，首次登录强制改密；重启不覆盖已修改密码。稳定通知加密key需按既有合同安全生成并在同库API/worker间一致，不能用示例/空key冒有效配置或隐式轮换。不要在环境文件中写shell表达式；systemd不是shell，示例占位符必须替换，程序不会自动source该文件。

## 3. 非root systemd

[服务模板](<../deploy/fire-platform.service>)是需管理员审阅后手动部署的样例，本项目不自动创建账号、安装、enable/restart服务。约定：

- 专用非登录用户/组`fire-platform`。
- `/opt/fire-platform/fire-platform.jar`由发布管理员维护、应用用户只读；先核当前包SHA。
- `/var/lib/fire-platform`为独立可写状态目录，既有数据不能用发布覆盖；JAR无需写安装目录。
- `/etc/fire-platform/platform.env`私密且独立备份/密钥管理。
- `/usr/bin/java`须为Java21；若选其它LinuxJDK，在管理员审阅的unit中改绝对路径。

模板启用NoNewPrivileges、PrivateTmp、ProtectSystem/ProtectHome及受限写目录；不要为方便用root跑应用。日志由journald收集并配置保留/访问策略，不能把原始body、令牌/密码或密钥加入日志。RestartSec与JVM内存等资源策略由管理员在unit/drop-in里按机器配置，不是普通客户业务配置；不要复制未经容量测量的堆大小承诺。

手工完成配置并检查后，管理员可执行：

```bash
sudo systemd-analyze verify /etc/systemd/system/fire-platform.service
sudo systemctl daemon-reload
sudo systemctl enable --now fire-platform
sudo systemctl status fire-platform
```

仅对已确认的本项目服务操作，不停止或改动机器上已有数据库/其它应用。此处是操作说明，不表示本轮已在用户主机部署服务。

## 4. 网络、角色与上线门禁

默认回环监听，使用受维护反向代理终止TLS；限制管理/API入口与数据库网络范围。显式信号功能启用时须精确HTTP(S) Origin，不能泛域/猜Host；票为同node，需要单API或经过验证的粘性方案。不得将head-only信号、有限重连或当前轮询称为无漏报/实体流保证。

单机使用 `all`；需要 `api`/`worker` 时共享同一 PostgreSQL 库与稳定密钥，分别配置权限和资源。健康检查角色名不证明相应消费者已运行；尚未验证的多机/HA 不作为生产支持目标。

上线前还需真实厂家/通知渠道联调、现有库升级/备份恢复、容量与故障演练、TLS与权限复核。模拟成功不代表真实送达，设备分组不授权、不修改物理事实。不能将本轮单机Ubuntu构建/私有验收当这些门已经通过。
