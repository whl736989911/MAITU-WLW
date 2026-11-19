package com.fireplatform.shared;

public record Pagination(int page,int size,long offset) {
    public static Pagination of(int page,Integer requestedSize,long defaultSize) {
        int size=requestedSize==null ? Math.toIntExact(defaultSize) : requestedSize;
        if(page<1 || page>Limits.MAX_PAGE) throw ApiError.bad("分页page无效");
        if(size<1 || size>Limits.MAX_PAGE_SIZE) throw ApiError.bad("分页size无效");
        return new Pagination(page,size,(long)(page-1)*size);
    }
}
