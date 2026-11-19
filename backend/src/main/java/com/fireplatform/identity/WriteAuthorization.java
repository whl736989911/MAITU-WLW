package com.fireplatform.identity;

/** Public identity boundary. Call inside a transaction; locks remain held until its commit. */
public interface WriteAuthorization {
    void lockAndAuthorize(AuthService.Identity identity, String permission, String tenant, String requiredAudience);
}
