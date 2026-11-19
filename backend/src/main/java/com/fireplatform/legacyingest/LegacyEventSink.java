package com.fireplatform.legacyingest;

/** 接缝：接入线在持久化原始报文后调用；事实线实现。 */
public interface LegacyEventSink {
    void accept(String messageId, LegacyNormalizedEvent event);
}
