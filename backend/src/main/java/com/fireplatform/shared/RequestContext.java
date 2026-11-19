package com.fireplatform.shared;

import jakarta.servlet.http.HttpServletRequest;

/** Safe audit metadata only. No shared dependency on identity's session representation. */
public final class RequestContext {
    public static final String REQUEST_ID = RequestContext.class.getName()+".requestId";
    private static final String ACTOR_ID = RequestContext.class.getName()+".actorId";
    private static final String TENANT_ID = RequestContext.class.getName()+".tenantId";
    private RequestContext() { }
    public static String requestId(HttpServletRequest request) { return (String) request.getAttribute(REQUEST_ID); }
    public static String actorId(HttpServletRequest request) { return (String) request.getAttribute(ACTOR_ID); }
    public static String tenantId(HttpServletRequest request) { return (String) request.getAttribute(TENANT_ID); }
    public static void actor(HttpServletRequest request, String userId, String tenantId) {
        request.setAttribute(ACTOR_ID,userId); request.setAttribute(TENANT_ID,tenantId);
    }
}
