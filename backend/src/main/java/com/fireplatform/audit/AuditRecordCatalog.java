package com.fireplatform.audit;

import java.util.*;
import java.util.stream.Stream;

/** Frozen source codes, not values discovered from potentially untrusted audit data. */
public final class AuditRecordCatalog {
    private AuditRecordCatalog() { }
    public static final String UNCLASSIFIED="UNCLASSIFIED";
    public record Definition(String code,String label) { }
    public static final List<Definition> ACTIONS=definitions("""
        LOGIN|登录
        CHANGE_PASSWORD|修改密码
        LOGOUT|退出登录
        REQUEST|请求鉴权
        BUSINESS_REQUEST|业务请求
        INVALID_REQUEST|请求校验
        CHANNEL_UPGRADE|信号通道握手
        CREATE_UNIT|创建单位
        UPDATE_UNIT|更新单位
        UPDATE_UNIT_DETAILS|编辑单位资料
        IDENTITY-DEPARTMENT-CREATE|创建部门
        IDENTITY-DEPARTMENT-UPDATE|编辑部门
        IDENTITY-USER-METADATA|编辑人员资料
        ROLE-CREATE|创建角色
        ROLE-UPDATE|编辑角色
        ROLE-DELETE|删除角色
        ALARM_BASE_CONFIG_UPDATED|编辑报警基础配置
        RESOURCE_LINKAGE_DEFAULT_UPDATED|编辑默认联动规则
        RESOURCE_LINKAGE_CREATED|创建联动规则
        RESOURCE_LINKAGE_UPDATED|编辑联动规则
        RESOURCE_LINKAGE_ENABLED|启用联动规则
        RESOURCE_LINKAGE_DISABLED|停用联动规则
        RESOURCE_LINKAGE_DELETED|删除联动规则
        RESOURCE_LINKAGE_EVALUATED|评估联动规则
        DEVICE_PORTRAIT_RULE_CONFIGURE|配置运维画像规则
        DEVICE_PORTRAIT_EVALUATE|分析运维画像
        DUTY_FAULT_HANDLING|处置故障
        SUPPRESSION_CREATED|创建通知屏蔽
        SUPPRESSION_CANCELLED|取消通知屏蔽
        SUPPRESSION_UPDATED|编辑通知屏蔽时段
        SUPPRESSION_ENABLED|启用通知屏蔽
        SUPPRESSION_DISABLED|停用通知屏蔽
        SUPPRESSION_DELETED|删除失效通知屏蔽
        DEVICE_CHANNEL_CREATED|登记人工传感器通道
        DEVICE_CHANNEL_UPDATED|编辑人工传感器通道
        CUSTOMER-CREATE|创建单位账号
        CUSTOMER-UPDATE|更新单位账号
        CUSTOMER-RESET|重置单位账号密码
        PLATFORM-CREATE|创建平台账号
        PLATFORM-UPDATE|更新平台账号
        PLATFORM-RESET|重置平台账号密码
        device-register|设备开户
        device-edit|编辑设备
        device-migrate|迁移设备版本
        product-create|创建产品
        version-create|创建产品版本
        version-edit|编辑产品版本
        version-test|验证协议解析
        version-publish|发布产品版本
        source-create|创建接入来源
        source-update|更新接入来源
        source-rotate|轮换来源凭据
        source-bind|绑定主源
        source-detach|解绑主源
        CAPTURE|捕获报文
        CAPTURE_DUPLICATE|记录重复报文
        MESSAGE_PREVIEW|报文归一化预览
        MESSAGE_APPLY|应用真实来源报文
        MESSAGE_AUTO_APPLY|自动应用真实来源报文
        hikvision-sync|请求海康真实同步
        hikvision-assign|分配海康来源设备
        hikvision-callback-config-update|更新海康回调配置
        hikvision-callback-subscriptions-read|只读查询海康订阅
        PARAMETER_SET|设置参数覆盖
        PARAMETER_CLEAR|清除参数覆盖
        UPDATE_SETTING|更新全局设置
        SIMULATE_FIRE|模拟火警
        SIMULATE_RECOVERY|模拟恢复
        SIMULATION_DEDUPLICATED|模拟消息去重
        REVIEW_ALARM|复核报警
        PROCESSING_SUBMIT|提交模拟处理任务
        PROCESSING_CANCEL|取消模拟处理任务
        PROCESSING_SUCCEEDED|模拟处理成功
        PROCESSING_REJECTED|模拟处理拒绝
        PROCESSING_RETRY|模拟处理待重试
        NOTIFICATION_CONTACT_WRITE|维护通知联系人
        NOTIFICATION_TEMPLATE_CREATE|创建通知模板
        NOTIFICATION_TEMPLATE_EDIT|编辑通知模板
        NOTIFICATION_RULE_WRITE|维护通知规则
        NOTIFICATION_QUEUED|模拟通知排队
        NOTIFICATION_CANCELLED|取消模拟通知
        NOTIFICATION_SIMULATION_RECONCILED|核对不确定模拟结果
        NOTIFICATION_REJECTED|模拟通知拒绝
        NOTIFICATION_SUPPRESSED|模拟通知抑制
        NOTIFICATION_SIMULATED_SUCCESS|模拟通知成功
        NOTIFICATION_SIMULATED_UNCERTAIN|模拟通知结果不确定
        NOTIFICATION_RETRY_SCHEDULED|安排模拟通知重试
        NOTIFICATION_SIMULATED_FAILURE|模拟通知失败
        NOTIFICATION_AUTOMATION_POLICY|设置自动模拟策略
        NOTIFICATION_AUTOMATION_QUEUED|自动模拟通知排队
        NOTIFICATION_AUTOMATION_ALREADY_DISPATCHED|已有模拟派发记录
        NOTIFICATION_AUTOMATION_SKIPPED|跳过自动模拟通知
        NOTIFICATION_AUTOMATION_REJECTED|拒绝自动模拟通知
        """);
    public static final List<Definition> OUTCOMES=definitions("""
        SUCCESS|操作成功
        DENIED|请求拒绝
        SUCCEEDED|操作已完成
        REJECTED|已拒绝
        RETRY|待重试
        SUCCEEDED_SIMULATION|历史模拟通知成功
        FAILED|操作失败
        UNCERTAIN|结果未确认
        WAIT_RETRY|等待重试
        SUPPRESSED|已抑制
        QUEUED|已排队
        ALREADY_DISPATCHED|已有派发记录
        SKIPPED|已跳过
        HANDLED|已处理
        UNHANDLED|未处理
        APPLIED|真实事实已应用
        STALE|已隔离过时消息
        REQUESTED|已请求
        """);
    public static final Set<String> ACTION_CODES=codes(ACTIONS),OUTCOME_CODES=codes(OUTCOMES);
    public static final String ACTION_SQL=classification("action",ACTIONS),OUTCOME_SQL=classification("outcome",OUTCOMES);
    private static List<Definition> definitions(String text) {
        var known=text.lines().filter(s->!s.isBlank()).map(s->s.split("\\|",2)).map(p->new Definition(p[0],p[1])).sorted(Comparator.comparing(Definition::code)).toList();
        if(known.isEmpty()||known.stream().map(Definition::code).distinct().count()!=known.size()||known.stream().anyMatch(d->!d.code().matches("[A-Za-z_-]+")||d.code().equals(UNCLASSIFIED)))throw new IllegalStateException("Invalid audit code registry");
        return Stream.concat(known.stream(),Stream.of(new Definition(UNCLASSIFIED,"未分类（原值不展示）"))).toList();
    }
    private static Set<String> codes(List<Definition> values){return Set.copyOf(values.stream().map(Definition::code).toList());}
    private static String classification(String column,List<Definition> values) {
        String literals=String.join(",",values.stream().filter(d->!d.code().equals(UNCLASSIFIED)).map(d->"'"+d.code()+"'").toList());
        return "CASE WHEN "+column+" IN ("+literals+") THEN "+column+" ELSE 'UNCLASSIFIED' END";
    }
}
