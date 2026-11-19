package com.fireplatform.notification.officialaccount;

import com.fireplatform.notification.NotificationChannelSupport;
import java.util.Set;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Declares the real WECHAT consumer to the rule-flow compiler.
 *
 * <p>Compilation only writes the same {@code notification_rule_receivers} rows the notification core
 * already reads. It never sends. Readiness, binding and delivery remain enforced at dispatch time.</p>
 */
@Component
@Primary
public class WechatOfficialAccountChannelSupport implements NotificationChannelSupport {
    private static final Set<String> SUPPORTED = Set.of("SMS", "VOICE", "WECHAT");

    @Override
    public Set<String> supported() { return SUPPORTED; }

    @Override
    public String rationale(String channel) {
        return switch (channel) {
            case "SMS" -> "短信由现有真实通知服务经 durable outbox、额度与回执链路发送。";
            case "VOICE" -> "语音由现有真实通知服务经 durable outbox、额度与回执链路发送。";
            case "WECHAT" -> "公众号模板由现有真实通知服务经同一规则、持久发送尝试与认证完成事件证明送达；未配置、未启用、未绑定、取消关注或单位通道策略未启用时不会伪造送达。";
            default -> "通知核心未声明该通道。";
        };
    }
}
