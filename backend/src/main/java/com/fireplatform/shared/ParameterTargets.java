package com.fireplatform.shared;

/** Minimal read-only target port; implementation must not depend on policy-consuming device queries. */
public interface ParameterTargets {
    record Target(String id,String name,String tenantId) { }
    Target require(String actor,String tenant,String device,boolean lock);
    Page<Target> list(String actor,String tenant,ScopeFilter.Selection selection,int page,Integer size,String keyword);
}
