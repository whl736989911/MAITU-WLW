package com.fireplatform.devicegroup;

import com.fireplatform.shared.*;
import java.util.*;

public record DeviceGroupFilter(String keyword) {
    public record Sql(String where,List<Object> arguments) { }
    public static DeviceGroupFilter parse(String value){return new DeviceGroupFilter(LiteralLike.normalizedKeyword(value));}
    public static Integer number(String value,Integer initial){if(value==null)return initial;if(!value.matches("0|[1-9][0-9]*"))throw ApiError.bad("分页参数须为规范整数");try{return Integer.valueOf(value);}catch(NumberFormatException e){throw ApiError.bad("分页参数超出范围");}}
    public Sql groups(String tenant){return tenant==null?plan("1=1",List.of(),List.of("g.name","g.description")):plan("g.tenant_id=?",List.of(tenant),List.of("g.name","g.description"));}
    public Sql devices(String tenant,String group,boolean candidates){String prefix;List<Object> initial;if(tenant==null){prefix=candidates?"1=1":"m.group_id=?";initial=candidates?List.of():List.of(group);}else{prefix=candidates?"d.tenant_id=?":"m.tenant_id=? AND m.group_id=?";initial=candidates?List.of(tenant):List.of(tenant,group);}return plan(prefix,initial,List.of("d.id","d.name","d.device_eui","d.address"));}
    private Sql plan(String prefix,List<Object> initial,List<String> columns){var where=new StringBuilder(" WHERE "+prefix);var arguments=new ArrayList<>(initial);if(!keyword.isEmpty()){where.append(" AND (");var parts=new StringJoiner(" OR ");for(String column:columns){parts.add("LOWER("+column+") LIKE LOWER(?) ESCAPE '!'");arguments.add(LiteralLike.contains(keyword));}where.append(parts).append(")");}return new Sql(where.toString(),List.copyOf(arguments));}
}
