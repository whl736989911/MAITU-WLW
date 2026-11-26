# 后端（仅Ubuntu/Linux）

Java 21、Spring Boot 4.0（Spring Framework 7、嵌入式 Tomcat 11）、JDBC/Flyway；生产使用 PostgreSQL 17，H2 仅用于开发和隔离测试。构建及运行在 Ubuntu/Linux；部署和备份要求见 [Ubuntu 部署手册](../docs/ubuntu-deployment.md)。

后端使用 [Spring Boot 4 官方 Jackson 2 兼容模块](https://docs.spring.io/spring-boot/4.0/reference/features/json.html#features.json.jackson2)。应用已有约 143 处 Jackson 2 调用，涵盖 API、来源原文、JSON 数字精度和日期偏移；本次只升级框架，不同时迁移序列化库。MVC 显式选用 Jackson 2，沿用严格解析、snake_case 与 JSON 授权输出锁。该模块已废弃；单独迁移 Jackson 3 时须重新验证这些行为。

开发与测试使用 H2 2.5.252；Boot 4.0 默认的 2.4.240 有[跨连接 CHECK 约束回归](https://github.com/h2database/h2database/issues/4291)。PostgreSQL 生产路径和迁移文件不变。

## 构建与启动

从工程根目录：

```bash
bash scripts/prepare-tools.sh   # 可选：本地Java21/Maven/Node24，不改系统工具
bash scripts/verify.sh
bash scripts/package.sh --skip-install
export APP_PROFILE=dev APP_STORAGE=h2-dev APP_BOOTSTRAP_USERNAME=admin
read -r -s -p '初始密码: ' APP_BOOTSTRAP_PASSWORD; printf '\n'
export APP_BOOTSTRAP_PASSWORD
bash scripts/run.sh
```

运行包输出到`backend/target`并包含前端；启动本身无需Node。已有Ubuntu JDK21可用`JAVA_HOME`/`PATH`，项目本地工具在`.tools/linux`。`APP_PORT`/`APP_BIND_ADDRESS`沿现设置，默认回环8080；脚本不自动读取私密文件。bootstrap至多按显式配置创建平台管理员，不创建默认单位、客户、设备或模拟业务种子；无默认登录密码，已有用户不会在重启时被bootstrap重置。环境变量以[模板](<../.env.example>)为准，资源策略集中配置并启动校验，不把开关当集群活跃心跳。

包含真实设备标识的私有回放样本不随源码发布。若需要运行 `LegacyIngestVectorTest` 的归档向量用例，设置 `LEGACY_APPENDIX` 为受控的解压目录（包含 `test-vectors/`）；不设置时仅跳过依赖归档的用例。已设置但缺少所需文件会导致用例失败。

仅导入旧产品时，将 `LEGACY_APPENDIX` 指向受控目录，包含 `legacy/products.tsv`、`legacy/product_attrs.tsv`、`excel-product-platform.tsv` 和 `codecs/`（获准内部使用的 `<p_id>.js`；缺失的解码器不补造）。通过管理端旧平台接入的字典预检和确认导入；不提供 `legacy/companies.tsv`、`legacy/places.tsv` 时，不创建组织、位置或单位授权。旧产品全名和 Excel 平台按产品版本保存，重导后可补齐既有版本的原名。同名产品找不到唯一平台对应时不自动取第一项。平台列仅用于建档匹配，不决定实际收包路由；导入解码器不等于已验证报文。

## 部署与存储

prod必须显式`APP_STORAGE=postgresql`，独立库和最小权限账号；设置`APP_DB_URL/APP_DB_USERNAME/APP_DB_PASSWORD`。通用认证来源应用worker、报警变更读取/发布/信号及真实通知均受各自配置与角色边界约束；默认发送/变更读取发布与信号关闭。启用真实外部渠道须按各自合同并经授权，不运行模拟业务。API与worker使用同库/稳定密钥，H2不作多进程共享生产库。Linux systemd以非root运行，私密环境文件由管理员保管，不自动安装/重启数据库服务。

Flyway 迁移见 `src/main/resources/db/migration`（SQL）和 `src/main/java/db/migration`（Java）。旧列和历史记录仅供追溯，不表示旧模板或模拟通知可用于当前写入。源码的迁移版本不证明远程数据库已升级；上线前须备份并实际演练升级和恢复。

框架切换不新增迁移，也不修改 V95。回退代码须使用已验证、同样包含 V95 的运行包；先核对 Flyway 历史与部署证据。仅按发布前备份和验证过的恢复流程处理数据库，不在产生新业务写入后直接恢复旧库。

## API与业务边界

- `/api` 使用 snake_case 与统一 `{code,message,request_id}` 错误。PLATFORM 汇总读可省略 `X-Tenant-Id`，始终与有效单位授权、单位启用状态和既有设备授权取交集。`scope_selected=true` 配空单位/位置集合表示空范围，省略筛选表示全部授权范围；单位与位置子树选择取并集。详情与敏感写仍按记录所属单位明确传头；CUSTOMER 固定会话单位，不接受管理端树范围扩权。详见[身份与范围合同](../docs/identity-contract.md)。
- [设备工作台](../docs/device-workbench-contract.md)、[设备档案](../docs/device-profile-contract.md)、[参数](../docs/parameter-contract.md)。
- [原始捕获与真实通用消息自动应用](<../docs/generic-message-apply-contract.md>)：仅认证来源、捕获时绑定的真实设备可应用；预览不改业务，`all`/`worker` 才运行自动应用worker，缺失/无效源事件时间拒绝。
- [真实短信/语音通知](<../docs/aliyun-notification.md>)：真实规则与provider配置取代模拟通知/旧模板操作；不要把旧模板或退役模拟合同作为当前API指南。
- [报警变更HTTP游标](<../docs/alarm-changes-contract.md>)、[显式信号通道](<../docs/alarm-change-channel-contract.md>)：游标和head按授权actor可见真实发布记录密集排名，完整性检查仍覆盖全部历史published记录；仅单一有效单位范围。
- [最小单位审计](../docs/audit-records-contract.md)。设备分组不得绕过设备读取或写入授权。

### 海康消防云

- **App / 表单登记：** [HF-FY500-G 用户手册](https://partners.hikvision.com/material-product/detail/985408157754245120?type=URL)公开产品 S/N；[消防设备添加 V2](https://open.hikfirecloud.com/artemis-portal/document/69#244)公开 `deviceSerial`、`verificationCode` 等字段，未发现该型号标签的原始二维码编码合同。App 只把扫码器已识别或安装人员核对的 S/N 原样作为 `external_serial`；校验码有明确来源才传 `verification_code`，不能从序列号推导。由有 `device:write`、`integrations:write` 和相应单位范围的管理端会话在用户确认后调用 `POST /api/management/vendor-devices/hikvision-create`。传 `Idempotency-Key`、`X-Tenant-Id` 及同一 `tenant_id`，请求选定 `place_id`、海康侧 `vendor_unit_id`、`device_name`、`device_model`、`device_type`、`communication_type`，并使用 `mode=NEW_VENDOR`、`driver_key=HIKVISION_FIRECLOUD`。厂商接收并返回云 ID 与本地建档是两个结果，均不代表设备已入网。
- **关联与核对：** 已发现设备使用 `POST /api/management/vendor-devices`（`source=HIKVISION_FIRECLOUD`）按精确序列号建档，或在海康接入页使用 `/api/management/hikvision/devices/{externalId}/assignment` 明确关联；这不是厂商在线/离线确权或联系人绑定。登记结果不明时走 `POST /api/management/device-registrations/{id}/reconcile`，不要重新新增。[消防设备查询 V3](https://open.hikfirecloud.com/artemis-portal/document/100#297)按既知云 ID 或序列号分页核对；响应不完整或超出核对上限时保留未知，不证明未找到或唯一匹配。
- **注销：** 管理端 `POST /api/management/devices/{id}/cloud-delete` 与本地解绑、归档独立。[消防设备删除 V2](https://open.hikfirecloud.com/artemis-portal/document/69#243)以云 `deviceID` 为目标；厂商接受且完整查询确认授权范围内记录不存在，才记为云记录删除已核实。设备断电、硬件注销、真实海康云写入和实际回调投递不属于本地测试的结论。
- **推送与补拉：** `POST /api/hooks/hikvision/{credential}` 验证已启用且绑定账号的 URL 凭证，把原始信封入库后返回空 HTTP 200；这是平台 ACK，未验证厂商 ACK/重试合同。默认只消费 `980016`，可显式启用[报警、故障和监测推送](https://open.hikfirecloud.com/artemis-portal/document/92#doc1)。厂商订阅写入须走 `/api/management/hikvision/subscriptions` 的预览、执行及回读，不自动注册或重发结果未知的写入。
- **历史与结果：** [V2 单位查询](https://open.hikfirecloud.com/artemis-portal/document/69#257)使用页号；报警/故障补拉分别查询发生和恢复时间，另读当前监测、监测历史及设备事件。推送与补拉沿用来源 ID 去重，区分来源与接收时间；移交后迟到的原事件仍归原单位，归属前或时序未知的记录不能写进新单位。仅有 `faultState=1` 表示来源确认恢复但未提供时间，不用接收时间补造，也不据此关闭火警。监测历史不冒充当前快照；当前监测按指标合并。回执 `PROCESSED` 仅表示已交解码器。同账号、同型号的 `PARTIAL`/`REJECTED` 可由有 `integrations:write` 的管理者以当前密码、修订和幂等键请求重解析；保留原文和首次接收时间，旧回执重放不代表实时处理结果。

回调处理只在`all`/`ingest`角色运行；`APP_HIKVISION_CALLBACK_POLL_MS`（`app.hikvision-callback-poll-ms`，默认1000ms，100..60000）和`APP_HIKVISION_CALLBACK_BATCH_SIZE`（`app.hikvision-callback-batch-size`，默认10，1..100）分别界定扫描间隔与每tick最大收件数。管理端`enabled`仅控制回调接收/处理，不依赖轮询配置开关；切换绑定海康账号须提供新的64位凭证。

## 手工内置资料与人工库存

详见[冻结业务合同](<../docs/sim-embedded-inventory-contract.md>)。两个独立域不导入旧台账/关联/设备业务实现，仅以同单位最小资源引用确认存在，不是第二个关联权威，也不连接运营商或处理真实物理库存。

- 内置资料由现有设备引用，ICCID可未知/null，也可后续人工更正，不合并实体或跨域查重。四个报告轴分别人工填写；不从EMBEDDED推断不可拆、付款人、管理方或停机状态。GET/POST `/api/management/embedded-sim-profiles` 与历史 `/api/management/embedded-sim-profile-changes`；精确Current15键、历史8键（含完整Profile15快照）。读取必须同时有 `device:read`、`sim-embedded:read`；登记另需 `sim-embedded:write`。
- 库存只引用既有REMOVABLE卡，GET `/api/management/sim-card-inventory` 只分页已登记row，`/{card_id}` 为Current6键，`/{card_id}/changes` 为历史10键。读取必须同时有 `sim-cards:read`、`sim-inventory:read`，写入另需 `sim-inventory:write`；旧metadata写、device写或关联权限都不隐含新权利。客户不获这四个新权限。
- 无row只读合成revision0/无登记时间；库存状态为UNREGISTERED，不把旧卡自动当作可用。每个新的、唯一接受的人工登记均从独立revision加1并追加历史，包括相同资料、相同库存目标状态；不是noop。MAX_SAFE拒绝全部新的登记。沿原集中分页政策，显式size仍检查page-size元数据。
- `receive/issue/recover/scrap` 可从非报废状态直接登记，不强加必须先入库/先解绑等流程。SCRAPPED仅接受重复scrap或显式 `correct`→AVAILABLE；correct必须当前已报废且有非空依据，保留旧报废和更正历史。解绑不自动回库，recover不自动解绑；领用去向只是普通文字，不是用户/设备目录身份或所属单位转移。
- 写入fresh当前安全上下文/密码后才检查完整原回执；重放不读取业务当前状态、不分配业务时钟、不再追加历史/审计/幂等行。保存回执是原登记快照，不承诺是“现在”。新登记采用实际可写RC与安全锁→资源锁→独立状态锁，state/history/audit/idem同事务。读取实际RO/RR，成功按当前schema核验全表零写（N02当时54表，V17为59表）；损坏源/缓存安全503，不补假空0。
- 日期保留真实0001..9999 Gregorian日；证据/登记时间以UTC fixed6微秒输出，不做毫秒截断。登记时间仅DB登记时刻，reason保完整原文、密码不落回执/审计。到期提醒是下节独立域，不创建无消费者30天参数或伪运营商开关。

历史验证材料不随源码公开；交付前应以当前代码重新生成 H2、PostgreSQL 和浏览器验收记录。

## SIM到期站内提醒

详见[冻结v2合同](<../docs/sim-expiry-reminder-contract.md>)。仅消费REMOVABLE台账及内置人工资料的到期DATE，不写回来源。

- 未配置为虚拟关闭revision0，时区、提前天数、重复间隔/次数均无有效默认值；启用必须明确完整配置。停用可保留完整值或明确清空。
- `sim-expiry:read`看轮次及个人已读；处理另需`sim-expiry:handle`；配置读取/维护、收件范围和显式扫描均需read+`sim-expiry:write`。客户无入口，无旧SIM权利的隐式升级。
- 收件范围按当前单位显式登记平台用户ID，不查询人员目录；enabled、已改密、grant和read权每次重新核验。每人已读独立，处理终结整轮。重复提醒不重置已读，规则变化/停启不自动重开已处理轮。
- 到期日变化由扫描结束旧OPEN轮；到新窗口创建新轮。内部source_revision与不可变来源历史识别A→B→A；显式重开创建新generation并保留旧HANDLED事实。同一目标最多一OPEN，同日只补一条，不循环补漏发。
- 现有`all`/`worker`角色自动扫描，`api`角色只提供接口；复用taskPollMs技术轮询，不用内存去重。安全管理锁→单位锁→提醒/来源锁，同事务RC；GET为RO/RR。自动扫描不外呼，不发硬件命令。
- `/api/management/sim-expiry`的config、recipients、rounds、scan及read/handle/reopen均按合同封闭字段；写必须当前密码和Idempotency-Key。原key回放提交时快照；无法确认的缓存不当当前业务状态。


## 真实Linux验证与历史

Python 夹具统一使用 Linux Java 21、PostgreSQL 17 与 `libpq.so`，仅启动自有非 root、回环、随机凭据的私有集群和 JVM；无系统数据库 fallback。`PG_BIN`/`LIBPQ_PATH` 可显式指定 Linux 工具。运行入口见[项目 README](../README.md)和[Ubuntu 部署手册](../docs/ubuntu-deployment.md)。


## 核心推送本轮验证（2026-11-12）

- 6个定向测试类共27项通过：推送/归一化、设备事件历史、故障查询及通用电量监测回归。
- 真实H2 HTTP链路与私有PostgreSQL17的V66→V67升级均通过：原登录与设备保留，既存V66观测与等价平铺推送去重，分项当前值合并，数值/单位保真，火警生成，故障恢复证据单调且不伪造恢复时间，收据重新解析CAS及原幂等回执。
- 以上为历史隔离验证，不代表当前构建或远程发布状态，不包括真实厂商订阅写入、硬件控制或收费通知。
