package com.fireplatform.identity;

import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.Limits;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class PermissionCatalog {
    public static final Set<String> CUSTOMER_KEYS=Set.of("summary:read","device:read","device:write","faults:handle","units:read","units:write","alarm:read","alarm:review","alarm:history","inbox:read","inbox:handle","notification-recharge:read","notification-recharge:write","users:read","users:write","users:reset-password","roles:read","users:assign-roles","users:assign-scope","unit-parameters:read","unit-parameters:write","unit-notifications:read","unit-notifications:write","imports:read","imports:write","exports:read","exports:write","device-services:read","device-services:query","device-services:execute","device-registrations:write","topology:read","video:read","video:play","controller:read","maintenance:read","maintenance:write","maintenance:assign","maintenance:review","fire-archives:read","fire-archives:write");
    public record Item(String key,String label,List<String> userTypes,boolean delegable) { }
    public record PasswordPolicy(int minLength,int maxLength) { }
    public record Catalog(List<Item> items,PasswordPolicy passwordPolicy) { }
    public Catalog forIdentity(AuthService.Identity identity) {
        Set<String> applicable=identity.audience().equals("customer") ? CUSTOMER_KEYS : Set.copyOf(Limits.PLATFORM_PERMISSIONS);
        return new Catalog(applicable.stream().sorted().map(key->new Item(key,label(key),CUSTOMER_KEYS.contains(key) ? List.of("PLATFORM","CUSTOMER") : List.of("PLATFORM"),identity.permissions().contains(key))).toList(),new PasswordPolicy(Limits.MIN_PASSWORD_LENGTH,Limits.MAX_PASSWORD_LENGTH));
    }
    public List<String> validate(String type,List<String> requested,List<String> actorPermissions) {
        if(requested==null || requested.size()>Limits.MAX_PERMISSION_ENTRIES) throw ApiError.bad("权限列表无效");
        Set<String> allowed=type.equals("CUSTOMER") ? CUSTOMER_KEYS : Set.copyOf(Limits.PLATFORM_PERMISSIONS);
        if(requested.stream().anyMatch(p->p==null || !allowed.contains(p) || !actorPermissions.contains(p))) throw new ApiError(403,"PERMISSION_DELEGATION_FORBIDDEN","不允许委派指定权限");
        return requested.stream().distinct().sorted().toList();
    }
    private String label(String key) {
        return switch(key) {
            case "device-services:read" -> "查看设备能力、参数与操作记录";
            case "device-services:query" -> "显式查询厂商设备参数与结果";
            case "device-services:execute" -> "执行已核验的普通设备服务";
            case "device-services:engineering" -> "平台工程高风险设备控制";
            case "device-registrations:read-global" -> "跨负责人查看未分配设备登记";
            case "device-registrations:write-global" -> "跨负责人处理未分配设备登记";
            case "device-registrations:write" -> "为本单位新增厂商设备登记";
            case "platform-ops:read" -> "查看平台发布、迁移与备份验证证据";
            case "applications:read" -> "查看应用、产品授权与数据输出结果";
            case "applications:write" -> "维护应用、产品授权与数据输出";
            case "applications:forward-raw" -> "授权单设备原始数据外发";
            case "topology:read" -> "查看授权网关与测点关系";
            case "topology:write" -> "维护网关与测点关系";
            case "video:read" -> "查看授权视频资源";
            case "video:write" -> "维护视频资源及关联";
            case "video:play" -> "播放及抓取授权视频画面";
            case "controller:read" -> "查看授权联动控制器";
            case "controller:write" -> "维护联动控制器关联";
            case "rules:read" -> "查看可视规则与消费版本";
            case "rules:write" -> "维护可视规则草稿";
            case "rules:publish" -> "发布可视规则到实际消费者";
            case "maintenance:read" -> "查看维保工单"; case "maintenance:write" -> "创建及处理维保工单";
            case "maintenance:assign" -> "派发维保工单"; case "maintenance:review" -> "复核维保工单";
            case "fire-archives:read" -> "查看消防档案"; case "fire-archives:write" -> "维护消防档案";
            case "payments:read" -> "查看支付配置与对账"; case "payments:write" -> "维护支付配置及核对订单";
            case "notification-delivery:reconcile" -> "查询厂商发送回执";
            case "ownership-exceptions:read" -> "查看平台未归属及归属时序异常元数据";
            case "integrations:read" -> "查看海康同步与待分配资源";
            case "integrations:write" -> "触发海康同步并分配到本地单位/位置";
            case "audit:read" -> "查看单位操作审计";
            case "alarm-base:read" -> "查看报警基础配置"; case "alarm-base:write" -> "维护报警基础配置";
            case "resource-linkage:read" -> "查看资源联动配置"; case "resource-linkage:write" -> "维护资源联动配置";
            case "sim-cards:read" -> "查看单位SIM手工台账"; case "sim-cards:write" -> "维护单位SIM手工台账";
            case "sim-cards:bind" -> "手工关联及解绑SIM设备";
            case "sim-embedded:read" -> "查看内置SIM人工资料"; case "sim-embedded:write" -> "登记内置SIM人工资料";
            case "sim-inventory:read" -> "查看SIM人工库存"; case "sim-inventory:write" -> "登记SIM人工库存";
            case "sim-expiry:read" -> "查看SIM到期提醒"; case "sim-expiry:handle" -> "处理SIM到期提醒"; case "sim-expiry:write" -> "配置SIM到期提醒";
            case "notification-config:read" -> "查看通知联系人和规则"; case "notification-config:write" -> "管理通知联系人和规则";
            case "unit-parameters:read" -> "查看本单位可覆盖参数"; case "unit-parameters:write" -> "维护本单位受限参数覆盖";
            case "unit-notifications:read" -> "查看本单位联系人和收件策略"; case "unit-notifications:write" -> "维护本单位联系人和受限收件策略";
            case "notification-resources:read" -> "查看通知资源账户"; case "notification-resources:write" -> "充值及调整通知资源";
            case "notification-recharge:read" -> "查看通知充值"; case "notification-recharge:write" -> "客户自主充值";
            case "summary:read" -> "查看概览"; case "device:read" -> "查看设备"; case "alarm:read" -> "查看报警"; case "alarm:review" -> "复核报警";
            case "roles:read" -> "查看角色模板"; case "roles:write" -> "维护全局角色模板";
            case "users:assign-roles" -> "分配单位用户角色"; case "users:assign-scope" -> "分配单位用户设备范围";
            case "platform-users:assign-roles" -> "分配平台账号角色"; case "platform-users:assign-scope" -> "分配平台账号设备范围";
            case "units:read" -> "查看单位管理"; case "units:write" -> "管理单位"; case "users:read" -> "查看单位用户"; case "users:write" -> "管理单位用户";
            case "users:reset-password" -> "重置单位用户密码"; case "platform-users:read" -> "查看平台账号"; case "platform-users:write" -> "管理平台账号"; case "platform-users:reset-password" -> "重置平台账号密码";
            case "products:read" -> "查看产品模板"; case "products:write" -> "编辑产品草稿与纯解析验证"; case "products:publish" -> "发布产品版本";
            case "device:write" -> "开户及编辑设备"; case "faults:handle" -> "本地故障处置（不改变来源状态）"; case "device:migrate" -> "显式迁移设备版本";
            case "tasks:read" -> "查看历史处理记录";
            case "sources:read" -> "查看接入来源"; case "sources:write" -> "管理接入来源"; case "sources:bind" -> "绑定及解绑主源"; case "sources:credentials" -> "轮换接入凭据";
            case "messages:read" -> "查看消息追踪"; case "messages:raw" -> "查看受限原文"; case "messages:preview" -> "归一化预览（不推进业务）"; case "messages:apply" -> "应用捕获消息（写设备事实与outbox）"; case "messages:reparse" -> "重解析消息（纯解析，无业务写入）"; case "suppressions:read" -> "查看通知屏蔽"; case "suppressions:write" -> "创建及取消通知屏蔽"; case "profiles:read" -> "查看设备画像"; case "profiles:write" -> "配置画像规则与重算"; case "imports:read" -> "查看导入任务"; case "imports:write" -> "批量导入设备"; case "exports:read" -> "下载导出快照"; case "exports:write" -> "创建导出快照"; case "inbox:read" -> "查看我的消息"; case "inbox:handle" -> "处理我的消息"; case "inbox:publish" -> "向单位客户投递站内消息"; case "retention:read" -> "查看数据保留策略"; case "retention:write" -> "配置保留策略并执行清理";
            case "parameters:read" -> "查看分层参数与来源"; case "parameters:write" -> "设置及清除离线阈值覆盖";
            case "alarm:history" -> "查看报警业务轨迹";
            default -> key;
        };
    }
}
