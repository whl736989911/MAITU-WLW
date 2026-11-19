package com.fireplatform.legacyingest.facts;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

@Component
public class ConnectivityDeriver {
    public enum State { ONLINE, OFFLINE, UNKNOWN, UNACTIVATED }

    /** Timed accepted heartbeat evidence enables interval inference; other reports only refresh lastMessageAt. */
    public State derive(OffsetDateTime lastMessageAt, OffsetDateTime lastHeartbeatAt, long offlineThresholdMinutes, OffsetDateTime now) {
        if(lastMessageAt==null)return State.UNACTIVATED;
        if(lastHeartbeatAt==null)return State.UNKNOWN;
        return lastMessageAt.plusMinutes(offlineThresholdMinutes).isBefore(now) ? State.OFFLINE : State.ONLINE;
    }
}
