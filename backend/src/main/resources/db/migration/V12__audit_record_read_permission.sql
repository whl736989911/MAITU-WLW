INSERT INTO user_permissions(user_id,permission)
SELECT u.id,'audit:read' FROM app_users u
WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE
AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='audit:read');
