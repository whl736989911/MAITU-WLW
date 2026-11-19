-- Only the protected platform bootstrap may delegate these new rights.
-- Existing customer accounts and roles receive no implicit self-service authority.
INSERT INTO user_permissions(user_id,permission)
SELECT u.id,p.permission FROM app_users u
CROSS JOIN (VALUES ('unit-parameters:read'),('unit-parameters:write'),('unit-notifications:read'),('unit-notifications:write')) p(permission)
WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE
AND NOT EXISTS(SELECT 1 FROM user_permissions existing WHERE existing.user_id=u.id AND existing.permission=p.permission);
