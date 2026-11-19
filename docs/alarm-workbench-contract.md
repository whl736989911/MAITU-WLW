# P6a2 双端报警筛选与同口径统计

## 已确认的业务决定

用户明确选择“不用，大家都能直接提交”：不增加领取/独占处理/领取超时，不改变原review的expected_revision+幂等与权限/CAS。之前选过10分钟，因最终不做领取，没有消费者，不新增/显示为生效配置。本轮不加handling_version/锁/ACK/关闭阶段，不发通知/下行，不伪造故障统计。保留P1..P6a1的100后端+134前端与所有旧API。

## 新增只读接口

GET /api/management/alarm-workbench、GET /api/customer/alarm-workbench；均当前alarm:read即可。管理显式当前X-Tenant-Id授权/启用单位，客户固定单位且拒绝tenant头。没有新权限，不依赖设备/产品/原文/history目录权。worker-only503。旧GET alarms/summary/current/history及POST review保持原合同，Dashboard compact仍可使用旧列表，完整报警页改用此工作台真实消费者。

查询参数（均可省略，不隐含今天/最近7天窗口）：
- page/size：现有Pagination、PageSizePolicy/default_page_size和Limits；不指定size就服务端默认，不硬编码20。
- keyword：报警记录的id、device_name/address快照字面包含检索，不查当前device/产品目录/EUI、不把%_!或SQL片段当通配或SQL。不区分ASCII大小写，Unicode沿数据库LOWER语义，不声称跨语言模糊搜索。复用集中keyword长度上界和LiteralLike，前后空白统一trim；空则无条件。
- physical_state：FIRE|NORMAL；response_state：UNHANDLED|HANDLED。
- conclusion：TRUE_FIRE|FALSE_ALARM|TEST|DEVICE_ALARM|UNREVIEWED，UNREVIEWED只映射SQL IS NULL，不存入结论字段。空字符串等同未指定；未知enum400，不静默当all。
- occurred_from、occurred_to：必须ISO8601含明确offset，合法微秒精度（nano%1000=0；更细精度400，不让PG隐式round改变边界）。过滤按alarms.occurred_at，>=from且<to；两者同时有必须from<to。可只填一边；非法时刻/无offset400，不取服务器/浏览器本地timezone猜。不限制未经批准的业务查询天数默认；年份须可在PG/H2安全支持公元0001..9999。

仅新工作台端点拒绝未知query key或任一重复query key（400），避免未应用的条件伪装成筛选，旧端点解析不动。所有条件AND，可合法选出无数据（如HANDLED+UNREVIEWED），不伪造一项自动覆盖另一项。排序仍occurred_at DESC,id ASC，当前页无数据不意味着total=0。QueryFilter解析/定义集中，SQL固定占位符拼接，不接受sort/列名表达式输入。

Response:
```
{
 items:AlarmRow[], total:integer, page:integer, size:integer,
 as_of:ISO8601,
 timezone:"Asia/Shanghai",
 filters:{keyword:string,physical_state:null|enum,response_state:null|enum,conclusion:null|enum,occurred_from:null|ISO,occurred_to:null|ISO},
 metrics:{alarm_total:integer,unhandled:integer,handled:integer,unrecovered:integer,recovered:integer,true_fire:integer},
 metric_definitions:{
   scope:"FILTERED_ALL_PAGES",
   time_field:"ALARM_OCCURRED_AT",
   time_bounds:"FROM_INCLUSIVE_TO_EXCLUSIVE",
   unhandled:"RESPONSE_UNHANDLED",
   unrecovered:"PHYSICAL_FIRE",
   true_fire:"CURRENT_CONCLUSION_TRUE_FIRE"
 }
}
```
AlarmRow与P6a1旧11字段相同，无remark/actor/原文/任务lease。filters是规范实际应用条件，不是回显未验证输入；occurred时间规范为UTC OffsetDateTime（Z）便于同义offset结果相同，keyword为trim后字面字符串。空keyword为""、其它无条件null。total=metrics.alarm_total；handled+unhandled=alarm_total；recovered+unrecovered=alarm_total；true_fire<=handled。值是数据库快照的真实0才显示0，不把错误/未知补0。

一次readOnly REPEATABLE_READ事务含page policy、DB实际as_of读取、SQL单次聚合统计（total直接来源aggregate，不另扫COUNT）、限定分页SELECT。聚合与页复用同一个where/bindings，无全量Java汇总/N+1。as_of只是查询评估记录，不是设备上报/报警新建；口径不是完整设备事件引擎或新报警created_at。当前true_fire依据当前有效复核结论，可改判；未恢复只按物理FIRE，HANDLED/FALSE_ALARM仍可未恢复。统计是所有匹配页，不是当前页或浏览器累计。

## 前端真实工作台

只改完整报警页，双端同组件/安全API，保留旧「查看」复核与「查看详情」/data-alarm-id和P6a1抽屉。明确标题“报警筛选与口径统计”，控件：关键字（报警ID/名称/地址快照）、物理状态、人工处置、复核结论、开始/结束时间；时间输入注明“北京时间 UTC+08:00，开始含/结束不含”，datetime-local字面补显式+08:00，不用new Date(localString)依赖浏览器timezone。无默认时间/enum/结论，初始all。

输入草稿不立即请求；显式「应用筛选」才以草稿查询并重置page1，不带硬编码size；「清空筛选」回到全量page1。页大小/翻页沿用既有控件，翻页使用已应用条件而不是尚未提交草稿。复核成功刷新现有条件统计/列表，不拿POST旧snapshot改计数；列表因匹配条件改变而移出不代表报警删除/恢复，当前已打开详情可继续独立查看。用户选不领取，此处不能出现claim/由我处理按钮。

显示“本次查询统计（所有匹配页）”、真实as_of/实际filters/六项口径；未处理、未恢复独立，真实火警写“当前复核为真实火警”不把每条FIRE/发生日期当已核实火灾。不得添加未实现的故障/通知卡、率/趋势/导出/WS承诺。空列表/错误/初载/当前页超界区分：错误/未读不画0；响应失配total/metrics/as_of/filters/enum损坏不画权威统计或伪正常，不默默采用旧筛选标题对应新列表。请求中隐藏/标记旧快照，响应须属于当前应用条件及单位generation；切单位/登出销毁草稿与已应用条件/旧指标/旧detail、取消请求，迟到旧响应不可串scope。

采用白名单decoder，客户不保留多余敏感字段；不把HTTP200或解析出的Number当充分证据，分页/指标须safe integer非负、总量关系一致、items身份/state已验证、as_of时间可解析、定义固定值匹配。已知页各类行数不能大于该类全量指标，全量结果都在本页时须精确相等；明确enum过滤也约束全量指标（如HANDLED→unhandled=0，UNREVIEWED→unhandled=total，实际非null结论→handled=total，TRUE_FIRE→handled=true_fire=total，FIRE→unrecovered=total），不接受与已知页/实际条件矛盾但分区和碰巧相等的数字。筛选配置与响应filters相符，坏metadata显式未确认；文字输入按React文本，无dangerouslySetInnerHTML。长字面关键字不会突破布局，新增控件窄屏可用，旧复核/详情布局保持。

## 实际验收出口

固定Clock/时刻边界+微秒，from包含/to排除，offset同义，显式bad timestamps/微秒外/未知enum/非法分页等400；字面%_!、SQL片段、不按当前device改名去查；组合条件/空结果/页外空items总数仍真实、统计基于所有页，CAS复核改判后重新统计而物理仍FIRE。

H2真实DataSource固定查询数/聚合+LIMIT/RR，writer在aggregate与page之间提交仍一致；没有新写或V9/权限表迁移需求。PG17实际fixture混合FIRE/正常、HANDLED/未处理、四种结论、明确时间、其它单位；SQL统计与返回行逐一核对、客户scope与最小alarm:read/无history/目录权限、非法输入不退有效会话、读操作零业务写。P5自然恢复不等于处理；没有用户未要的领取副作用。

Chrome实际双端草稿/应用/clear/分页（默认page_size配置）、UTC浏览器也按北京时间发出offset、已应用标签与实际network过滤一致；普通文本注入无执行、复核后保留筛选并重读口径、不隐式领取/恢复、切scope销毁/晚结果忽略、390px布局。最终完整包回归P1..P6a1/P3b1，100+134基线全部保留，源代码审阅不冒充运行/生产/厂家或跨机验证。
