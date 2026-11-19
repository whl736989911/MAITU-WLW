-- One-time upgrade of the original protected platform administrator only.
-- No customer, ordinary account, role template or startup-time regrant is changed.
INSERT INTO user_permissions(user_id,permission)
SELECT u.id,p.permission FROM app_users u
CROSS JOIN (VALUES
    ('device-services:read'),('device-services:query'),('device-services:execute'),('device-services:engineering'),
    ('device-registrations:read-global'),('device-registrations:write-global'),
    ('device-registrations:write'),
    ('platform-ops:read'),
    ('applications:read'),('applications:write'),('applications:forward-raw'),
    ('topology:read'),('topology:write'),('video:read'),('video:write'),('video:play'),('controller:read'),('controller:write'),
    ('rules:read'),('rules:write'),('rules:publish'),
    ('maintenance:read'),('maintenance:write'),('maintenance:assign'),('maintenance:review'),
    ('fire-archives:read'),('fire-archives:write'),
    ('payments:read'),('payments:write'),('notification-delivery:reconcile'),('ownership-exceptions:read')
) p(permission)
WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE
AND NOT EXISTS(SELECT 1 FROM user_permissions existing WHERE existing.user_id=u.id AND existing.permission=p.permission);
