# H2 → PostgreSQL 17 切库（cutover）迁移说明

本文档只描述**隔离的 H2 快照到 PostgreSQL 17 切库**。这不是生产切流、账目迁移、
通知重发或设备控制的运行授权。

## 1. 工具与范围

- 切库入口：`com.fireplatform.ops.H2PostgresCutover`（`backend/src/main/java/com/fireplatform/ops/H2PostgresCutover.java`），
  是一个独立的 JDBC/Flyway 命令行工具，不启动 Spring 应用或 Bootstrap，只连接环境变量指定的两个 JDBC URL。
- 运行脚本：`scripts/ops/cutover.sh`，通过 Spring Boot `PropertiesLauncher` 调用上面的主类，
  从已打包 JAR 加载。默认是只读计划（dry-run），只有显式 `--execute` 才会对目标库做任何 DDL/DML。
- 目标库要求：PostgreSQL 17、UTF8 编码、`public` schema 完全为空（无表、无例程、无自定义类型）。
  空 schema 被两处校验（Flyway 前与 Flyway 前一刻），任一不满足即拒绝，绝不删除或覆盖已有对象。
- 源库要求：本地 H2 文件，URL 必须带 `IFEXISTS=TRUE;ACCESS_MODE_DATA=r;LAZY_QUERY_EXECUTION=TRUE`，
  工具还会拒绝 `;INIT=`、`;AUTO_SERVER`、`;CREATE=TRUE`、内存/TCP/ZIP 等危险设置，并检测 `.lock.db`
  要求 H2 已干净停库。源 H2 全程只读，字节不变。

## 2. 历史来源归属的保全

- 除 `flyway_schema_history` 外，**所有业务表按名称匹配逐表复制**，不按表名白名单挑选；
  设备位置历史（`device_place_history`）、来源绑定（`device_source_bindings`）、报警轨迹、
  审计、通知尝试、原文报文等历史归属数据都随表整体搬运，不改写历史归属、原文或命令。
- 源与目标的表结构（列名、逻辑类型、数值精度/标度、文本/二进制声明长度、主键）在复制前逐表比对，
  不一致即失败，不静默丢列或改类型。
- 源 H2 的 Flyway 历史表整体复制到目标，源校验和（checksum）集合经 Flyway `validate` 验证，
  目标切库后 `current` 版本必须等于 `--expected-version`，且无 pending 迁移。这样切库后 Flyway
  不把既有迁移重新执行，也不漂移历史。
- 边界：该工具不处理自动增长/identity 列（本 schema 无此列），不处理分区表/外部表/视图
  （视图由 Flyway 在目标重新创建）。序列计数器若存在会被明确拒绝，而非悄悄错位。

## 3. 影子核对（复制前后语义摘要比对）

- 每张表按主键确定性排序流式读取源行，写入目标后**再流式读回目标**，用相同的规范化摘要
  （文本 UTF-8、DECIMAL 规范化、TIMESTAMPTZ 转 Instant、JSON 键排序 + 浮点规范化）分别计算
  SHA-256；行数不一致或摘要不一致即抛出失败并回滚整个事务。
- 外键在复制前捕获并删除，复制后重建并对每条执行 `VALIDATE CONSTRAINT`，切库后再次核对外键
  数量与 `convalidated` 状态，防止外键被静默跳过或未校验。
- 在打开源 H2 前读取 `.mv.db` 的 SHA-256，关闭源连接后再读一次。执行模式在提交数据前要求两次
  摘要相同；计划模式在报告成功前核对。私有 manifest 的 `source.sha256Before`、`source.sha256After`
  与 `source.fingerprintMatches` 记录结果；无后置摘要不是保全证明。
- `--manifest-out` 不得是源文件或其别名。把回执放在仅操作者可写的目录，不公开回执内容。
  回执不记录源 JDBC URL 或源文件路径；还记录版本、每表行数与摘要、外键与 Flyway 历史校验。

## 4. 验收回退

- 数据复制事务中失败会尝试 `rollback`，由 `rollback.attempted/succeeded` 区分是否调用及是否成功。
  Flyway 在数据事务前独立提交 DDL 和初始种子；即使数据成功回滚，目标也不是空库，不能就地重试。
  保留失败回执，重新演练时使用另一个全空的独立库。
- `commitOutcome` 是 `NOT_ATTEMPTED`、`UNKNOWN` 或 `COMMITTED`；`committed=true` 仅表示数据库确认提交。
  提交调用失败时即使回滚调用返回成功，也不能排除已提交；提交后校验或落盘回执失败也不等于回滚。
  无回执时先看 CLI 的 `target commit=...`，再只读核对数据库，不盲目重试或覆盖目标。
- 未向目标开放业务写入、且源文件已验明可用时，验收失败可回指原 H2，不做反向迁移。
- 目标侧一旦提交，后续回退用 `scripts/ops/restore.sh`：确认前只显示计划；执行时先保存独立的
  pre-restore 自定义格式备份，再在单一事务中清理并恢复。出错不提交部分变更，保留 pre-restore
  备份。定时归档使用 `scripts/ops/backup.sh`，新建归档仅操作账户可读写。
  回退到 H2 与回退到 PG 备份是两回事，不混用。
- 回退边界：切库工具**不删除源 H2**、**不在产生新业务写入后自动恢复旧库**、**不做反向 PG→H2**。

## 5. 操作顺序（运行前必须由管理员在独立环境演练）

1. 停 H2 应用，确认 `.lock.db` 不存在；使用受控快照，切库期间不允许第二个进程修改源或目标。
2. 建一个完全空的 PostgreSQL 17 数据库（UTF8），不与其它应用共用；确认回执目录和文件路径可写。
3. 首次只跑计划：`bash scripts/ops/cutover.sh --expected-version <当前H2版本> --manifest-out <私有路径>`，
   在 manifest 核对表数、行数和源文件指纹。
4. 演练通过后，用另一个私有回执路径加 `--execute` 执行；核对 `status=COMPLETED`、
   `commitOutcome=COMMITTED`、`source.fingerprintMatches=true` 和 `foreignKeys.validated=true`。
5. 用应用指向目标 PG（`APP_STORAGE=postgresql`）做一次只读验收；回指 H2 前核对是否已有新业务写入。

凭据只经环境变量注入（`FIRE_CUTOVER_*`），脚本不读 argv/文件、不打印明文、不写仓库。

## 6. 验证状态

- 静态：`scripts/ops/verify.sh` 对 `backup.sh restore.sh healthcheck.sh install-postgresql17.sh cutover.sh`
  做 `bash -n` 与（可选的）`shellcheck`；systemd 单元做 `systemd-analyze verify` 或静态断言。
- 隔离集成测试：使用自有、仅监听回环的独立 PG17 测试实例，向 `IOT_MIGRATION_PG_URL` 指定其
  `postgres` 数据库，并通过 `IOT_MIGRATION_PG_USER`、`IOT_MIGRATION_PG_PASSWORD` 提供夹具凭据。
  运行 `mvn -B -ntp -f backend/pom.xml -Dtest=H2PostgresCutoverIntegrationTest test`。测试使用离线合成 H2，
  覆盖原文及时间保全、历史归属和 UNKNOWN、真实外键回滚、回执碰撞、提交后回执写入失败。
- Ubuntu 26.04 首次演练因 WSL I/O 错误中止；后续在独立、仅回环监听的原生 PostgreSQL 17.11 上完成 V94 演练。迁移、升级、提醒定向测试共 8 项通过；实际 CLI 以含空格和中文的只读 H2 路径、UTF8 及 IPv6 目标执行，迁移 202 张业务表、18 条合成记录，核验 381 个外键，源 H2 摘要不变。验证了外键回滚、回执失败、提交确认丢失时报告未知及拒绝向非空目标重复执行。合包中迁移资源与切库类和演练包逐字节一致；发布其它产物前必须重新核对。
- **未验收**：未使用生产数据、执行生产切流、真实付费通知或真实设备写操作。
