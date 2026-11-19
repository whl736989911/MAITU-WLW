# N17：性能、故障与分工验证（Frozen v1，实施合同）

## 目标与边界

在本机可完成的范围内验证**进程分工与并发领取**，并保留无法验证部分的门禁。

- 已验证：独立 PostgreSQL 上 `api` 进程不消费任务；两个 `worker` 进程并发领取时，每个任务**恰好被处理一次**；记录 JVM RSS 采样。
- 未验证且不冒认：真实裸机、跨机部署、高可用/SLA、真实负载曲线、连接池饱和阈值。
- 崩溃恢复由自动化测试覆盖（`ReparseJobTest.expiredLeaseIsRecoveredAsNewAttempt`、`ProcessingIntegrationTest` 的租约场景），本演练不重复伪造。

## 演练脚本

`scripts/dev/n17-concurrency-drill.sh`（本机 WSL + 独立 PG）：

1. `storage-env.sh` 以 `postgresql` 启动临时 PG（临时数据目录）；
2. 起 `--app.roles=api` 进程，完成 bootstrap 与种子（产品/设备/来源/两条消息）；
3. 通过 API 创建 2 个重解析任务，等待 3 秒 → 期望仍为 `PENDING`（证明 API 进程不消费）；
4. 起两个 `--app.roles=worker --app.reparse-enabled=true` 进程；
5. 轮询至终态，采样三个 JVM 的 `VmRSS`；
6. 校验每个任务 `attempt_count == 1`；
7. 输出 `{ok, api_only_statuses, final_statuses, attempt_counts, rss_kb}`。

## 实测结果

```json
{"ok":true,"storage":"postgresql",
 "api_only_statuses":["PENDING","PENDING"],
 "final_statuses":"SUCCEEDED,SUCCEEDED",
 "attempt_counts":[1,1],
 "rss_kb":{"api":327512,"worker1":294684,"worker2":306824}}
```

RSS 只是本机观测，不构成容量承诺；真实容量需独立压测与生产环境。

## 门禁

- 裸机/跨机/HA/SLA/真实厂商链路：未具备，保留独立批准与环境要求。
- 生产容量、连接池与长稳曲线：未验证，不得以本演练替代。
