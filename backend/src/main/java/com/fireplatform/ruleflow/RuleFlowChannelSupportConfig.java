package com.fireplatform.ruleflow;

import com.fireplatform.notification.NotificationChannelSupport;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fallback channel support: only the two channels the real notification service currently delivers.
 * A future channel owner overrides this bean; if a channel is listed here without a real consumer the
 * compiler only ever writes rows the notification core already reads, never a second sender.
 */
@Configuration
public class RuleFlowChannelSupportConfig {
    @Bean
    @ConditionalOnMissingBean(NotificationChannelSupport.class)
    public NotificationChannelSupport ruleFlowChannelSupport() {
        return new NotificationChannelSupport() {
            private final Set<String> supported = Set.of("SMS", "VOICE");

            @Override public Set<String> supported() { return supported; }

            @Override public String rationale(String channel) {
                return switch (channel) {
                    case "SMS" -> "短信由现有真实通知服务经 durable outbox、额度与回执链路发送。";
                    case "VOICE" -> "语音由现有真实通知服务经 durable outbox、额度与回执链路发送。";
                    case "WECHAT" -> "通知核心当前未声明微信通道消费者；公众号通道未接通时规则流不能生成微信推送。";
                    default -> "通知核心未声明该通道。";
                };
            }
        };
    }
}
