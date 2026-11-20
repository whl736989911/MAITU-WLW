# Ubuntu部署手册

## 唯一支持平台与前置条件

构建及部署仅支持 Ubuntu/Linux。目标运行环境为 Ubuntu 26.04 LTS；Java 21、PostgreSQL 17 和后端内嵌前端组成生产运行环境。构建需要 Maven 3.9.11 和 Node 24，自动验收需要 Python 3.12+。Ubuntu 默认仓库的 PostgreSQL 版本未必是 17，须按受维护渠道安装。

后端使用 Spring Boot 4.0；Java 仍为 21，生产数据库仍为 PostgreSQL 17。发布前以实际 JAR 中的依赖版本核对维护状态和厂商安全公告，不只看声明在 `backend/pom.xml` 中的版本。升级前保留开发环境的 H2 文件副本；不要用新版 H2 直接打开唯一的旧文件。

构建、迁移验证与生产上线分开。WSL 本地预览不能代替独立生产主机的内核、容量、网络或灾备验收。

## 1. 构建

```bash
bash scripts/prepare-tools.sh   # 可选，仅校验安装项目.tools/linux，不改系统PATH/服务
bash scripts/package.sh        # 已包含验证和打包
```

已有Java21/Node24/Maven可直接用Linux PATH/JAVA_HOME。打包失败即停，必须保留实际日志，不删测/跳test或用旧包顶替。复制新构建的`backend/target/fire-platform-backend-0.1.0-SNAPSHOT.jar`至版本化发布目录，核SHA后再切换稳定路径；不得复制其它系统的node_modules/target/dist。使用[根说明](<../README.md>)列出的当前Linux入口。

公开源码可在不提供旧平台私有归档的情况下打包：合成解码、沙箱异常和公开样本仍执行。需要核对私有历史向量时，另行显式设置 `LEGACY_APPENDIX`；配置后缺文件会导致测试失败。即使使用 `--skip-install`，`scripts/codec-sandbox/node_modules` 也须在沙箱目录内实际安装；不要软链接到权限边界外。

## 2. 数据库与私密配置

先建独立PG17数据库和最小权限应用角色，不与别的应用共用管理员身份。应用启动会执行Flyway，先对备份/隔离副本演练V13升级及真实恢复；禁止把第一次演练指向生产库。H2仅dev/test，不用于多进程共享或生产容量承诺。

H2 升级还会影响 JDBC 唯一约束的错误格式。升级后须运行设备分组、SIM 台账及 SIM 绑定回归测试，确认只把已知业务约束转换为 HTTP 409，其他数据库错误仍按原路径处理。

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

- 旧平台镜像入口使用 `FIRE_LEGACY_MIRROR_TOKEN`，直接上报使用独立的 `APP_LEGACY_INGEST_TOKEN`。Turmass 按已授权的 Broker 和主题设置 `FIRE_LEGACY_MQTT_*`，使用只读订阅。历史归档人工导入另设 `LEGACY_APPENDIX`（包含 `legacy/` 和 `codecs/`）及可选的 `LEGACY_PLACE_HIERARCHY_EXPORT`；实时采集不需要这些目录。
- 阿里云凭证、短信签名与模板、语音模板、发送通道开关和 HTTPS 地址在管理端通知通道配置保存，以数据库配置为准。连接、请求超时留在服务环境；真实发送开关必须单独启用。
- 管理端配置设备上报超时、报警升级间隔和重复提醒周期。`APP_LEGACY_OFFLINE_*` 和 `FIRE_ALARM_REMINDER_*` 只改变后台扫描频率，不改变业务阈值。

### PostgreSQL 备份与恢复演练

- 演练使用专用 PostgreSQL 实例、独立目录和独占回环端口（或私有 Unix socket）、合成归档和只有演练库权限的角色。不要载入生产服务环境、凭据或备份目录。`backup.sh` 会按保留策略清理输出目录中的旧归档；演练须显式设置 `BACKUP_DIR`、`BACKUP_DATABASE` 和隔离连接（`PGHOST`、`PGPORT`、`PGUSER`），不能使用生产调度环境。
- `restore.sh` 必须显式传入单一 `--host`、`--port` 和纯数据库名 `--database`（不接受 URI 或连接串）。预备份和恢复使用同一组参数；继承的 `PGHOSTADDR`、`PGSERVICE` 不参与连接。演练用确切回环地址或私有 socket，不用可能转发到生产的名称或代理。

```bash
export BACKUP_DIR=/path/to/private-rehearsal-backups
export PGUSER=fire_platform_rehearsal
bash scripts/ops/restore.sh /path/to/synthetic.dump \
  --host 127.0.0.1 --port 55432 --database fire_platform_rehearsal
```

确认计划显示的主机或 socket、端口、库名和归档路径均属于隔离实例，再用相同参数加 `--yes`。脚本先保存独立的 `pre-restore` 归档，再在单事务中清理并恢复归档所列对象；不会移除目标库中所有未列出的对象。恢复后核对行数和业务不变量，不以退出码或健康状态代替验收。H2 切库同样只使用单一、显式的隔离 JDBC 端点及全空目标，先查阅 `cutover.sh` 的只读计划，再决定是否 `--execute`。

生产恢复不是演练。实际端点、角色权限、备份保留和可恢复性、停写及回退界线须在目标机由管理员核验。目标已有新业务写入或写入状态不明时，不得直接覆盖或回指旧库。

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

## 5. 双平台并行与旧平台退役

数据库迁移和业务切流分开验收。数据库遵循 [H2 → PostgreSQL 17](ubuntu-h2pg-cutover.md) 的只读源、空目标和备份回退要求；它不改变设备归属、第三方接入或通知发送路径。

1. **并行观察**：旧平台保持当前值守及付费通知职责。新版只接收已授权的旧 HTTP 镜像、Turmass 只读 MQTT，以及已授权的海康云轮询或回调。新版 `FIRE_NOTIFICATION_REAL_ENABLED=false`，不执行云新增、删除或下行操作。先确认两个平台的告警各自可见，再启用试点。
2. **核对试点**：为每批设备明确单位、实际位置、来源账号、标识和生效时间。按来源核对上报、火警、故障、恢复；把未归属、缺来源时间、重复或无法精确关联的记录单列，不自动合并、不补造恢复、不回放历史触发外呼。旧来源使用「接入镜像／对账差异／归属异常」，海康使用「海康云接入」的发现与导入状态，最终以「报警信息」和「通知发送记录」核对效果。
3. **接管通知**：为试点写明观察开始/结束时间、比对数量、差异及责任人。先使用明确同意的试验收件人核验一条短信和一次语音，分别记录供应商受理、短信送达、语音接通和独立扣量。对实际接管范围明确哪个平台发送；不能做到按范围互斥时，不进行重叠启用。无真实恢复、未解释的漏报/误归属、回执未知或重复计费均阻断退役。
4. **退役与回退**：经责任人验收后，只停止旧平台的接入/独占值守职责，不停止新版海康接入；先保留原路径、审计和可恢复备份。发生故障先暂停新版外部发送，确认未决发送和已发生业务写入，再恢复旧平台对应职责，避免重复通知。目标库已有新业务写入后不得直接回指旧库。

本机隔离的 [HTTP 与 Turmass 双入口烟测](../scripts/smoke/README.md) 不使用真实设备、Broker 或收件人；通过不能作为试点观察、真实送达或旧平台退役的依据。
