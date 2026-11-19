package com.fireplatform.shared;

import java.nio.CharBuffer;
import java.nio.charset.*;

/** Bounded, literal case-insensitive LIKE with an explicit ! escape. No SQL interpolation. */
public final class LiteralLike {
    private LiteralLike() { }
    public static String normalizedKeyword(String input) {
        if(input!=null && input.indexOf(0)>=0) throw ApiError.bad("搜索关键字无效");
        // Match browser String.trim whitespace, including Unicode separators and BOM, without stripping NUL.
        String value=input==null?"":input.replaceAll("^[\\s\\p{Z}\\uFEFF]+|[\\s\\p{Z}\\uFEFF]+$","");
        if(value.length()>Limits.MAX_KEYWORD_LENGTH || value.indexOf(0)>=0) throw ApiError.bad("搜索关键字无效或过长");
        try { StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)); } catch(CharacterCodingException e) { throw ApiError.bad("文本编码无效"); }
        return value;
    }
    public static String contains(String normalized) { return "%"+normalized.replace("!","!!").replace("%","!%").replace("_","!_")+"%"; }
}
