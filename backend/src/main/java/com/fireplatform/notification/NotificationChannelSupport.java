package com.fireplatform.notification;

import java.util.Set;

/**
 * Published capability description of the notification core: which channels it can actually consume and
 * deliver. It lives with the notification module that owns delivery; the rule-flow compiler consumes it and
 * never pretends a channel works. A channel owner registers the bean when the channel becomes real; until then
 * a flow referencing it fails compilation with a per-node reason.
 */
public interface NotificationChannelSupport {
    /** Channel codes accepted by {@code notification_rule_receivers.channel} and delivered by the real notification service. */
    Set<String> supported();

    /** Why a channel is or is not available; shown verbatim as the compile reason. */
    String rationale(String channel);
}
