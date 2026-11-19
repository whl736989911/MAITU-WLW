package com.fireplatform.simcard;

import com.fireplatform.shared.*;
import java.util.*;

public record SimCardFilter(String keyword){
    public record Sql(String where,List<Object> arguments){ }
    public static SimCardFilter parse(String value){return new SimCardFilter(LiteralLike.normalizedKeyword(value));}
    public static Integer number(String value,Integer initial){if(value==null)return initial;if(!value.matches("0|[1-9][0-9]*"))throw ApiError.bad("分页参数须为规范整数");try{return Integer.valueOf(value);}catch(NumberFormatException e){throw ApiError.bad("分页参数超出范围");}}
    public Sql cards(String tenant){
        if(keyword.isEmpty())return new Sql(" WHERE c.tenant_id=?",List.of(tenant));
        String literal=LiteralLike.contains(keyword);return new Sql(" WHERE c.tenant_id=? AND (LOWER(c.iccid) LIKE LOWER(?) ESCAPE '!' OR LOWER(c.carrier_name) LIKE LOWER(?) ESCAPE '!' OR LOWER(c.source_note) LIKE LOWER(?) ESCAPE '!')",List.of(tenant,literal,literal,literal));
    }
}
