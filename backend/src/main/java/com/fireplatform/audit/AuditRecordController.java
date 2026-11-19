package com.fireplatform.audit;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuditRecordController {
    private static final Set<String> KEYS=Set.of("page","size","action","outcome","actor_ref","target_ref","occurred_from","occurred_to","keyword","username","module","platform","scope_selected","scope_tenant_ids","scope_place_ids");
    private final RequestSecurity security;private final AuditRecordQueries queries;private final AuditLogExport exports;
    public AuditRecordController(RequestSecurity security,AuditRecordQueries queries,AuditLogExport exports){this.security=security;this.queries=queries;this.exports=exports;}
    static Integer number(String value,Integer initial){if(value==null)return initial;if(!value.matches("0|[1-9][0-9]*"))throw ApiError.bad("分页参数须为规范整数");try{return Integer.valueOf(value);}catch(NumberFormatException e){throw ApiError.bad("分页参数超出范围");}}
    @GetMapping("/api/management/audit-records")
    public AuditRecordQueries.Result query(HttpServletRequest request){
        var input=input(request);
        return queries.query(input.actor(),number(request.getParameter("page"),1),number(request.getParameter("size"),null),input.filter(),security.readSelection(request),input.search(),input.access());
    }
    @GetMapping("/api/management/audit-records/export")
    public void export(HttpServletRequest request,HttpServletResponse response) throws IOException {
        security.require(request,"exports:read","management");
        var input=input(request);var selection=security.readSelection(request);
        if(request.getParameter("page")!=null||request.getParameter("size")!=null)throw ApiError.bad("日志导出覆盖全部筛选结果，不接受分页参数");
        response.setCharacterEncoding("UTF-8");response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition","attachment; filename=\"operation-logs.csv\"");
        response.setHeader("X-Content-Type-Options","nosniff");
        exports.write(input.actor(),input.filter(),selection,input.search(),input.access(),response.getWriter());
    }
    private record Input(String actor,AuditRecordFilter filter,AuditLogSearch search,AuditLogSearch.Access access) { }
    private Input input(HttpServletRequest request) {
        var identity=security.require(request,"audit:read","management");if(!"PLATFORM".equals(identity.user().userType()))throw ApiError.forbidden("仅平台账号可读取单位审计");
        if(request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null)throw ApiError.bad("只读审计查询不接受请求体");
        for(var entry:request.getParameterMap().entrySet())if(!KEYS.contains(entry.getKey())||entry.getValue().length!=1)throw ApiError.bad("只读审计查询参数未知或重复");
        var filter=AuditRecordFilter.parse(request.getParameter("action"),request.getParameter("outcome"),request.getParameter("actor_ref"),request.getParameter("target_ref"),request.getParameter("occurred_from"),request.getParameter("occurred_to"));
        var access=new AuditLogSearch.Access(identity.permissions().contains("users:read"),identity.permissions().contains("platform-users:read"));
        var search=AuditLogSearch.parse(request.getParameter("keyword"),request.getParameter("username"),request.getParameter("module"),request.getParameter("platform"),access);
        return new Input(identity.user().id(),filter,search,access);
    }
}
