package com.fireplatform.notification;

import com.fireplatform.shared.*;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.*;

public final class NotificationPolicy {
    private NotificationPolicy() { }
    public static final List<String> CONFIG_RIGHTS=List.of("notification-config:write","notification-config:read");
    public static final List<String> TYPES=List.of("FIRE_RESPONSIBLE","LEGAL_REPRESENTATIVE","CONTROL_ROOM","DUTY_OFFICER","OTHER");
    public static String text(String value,int max) { if(value==null || value.isBlank() || value.length()>max || value.indexOf(0)>=0) throw ApiError.bad("通知输入文本无效或过长"); try { StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)); } catch(CharacterCodingException e) { throw ApiError.bad("通知文本编码无效"); } return value; }
    public static String id(String value) { return text(value,64); }
    public static boolean flag(Boolean value) { if(value==null) throw ApiError.bad("必须明确选择启用状态"); return value; }
    public static long number(Long value,long min,long max) { if(value==null || value<min || value>max) throw ApiError.bad("通知整数配置超出允许范围"); return value; }
    public static void revision(Long expected,long current) { number(expected,0,Long.MAX_VALUE); if(expected!=current) throw ApiError.conflict("REVISION_CONFLICT","对象已更新，请刷新后重开"); }
    public static String choice(String value,List<String> allowed) { if(value==null || !allowed.contains(value)) throw ApiError.bad("通知枚举值无效"); return value; }
}
