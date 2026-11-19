"""Finite black-box formal onboarding / revocation checks, on H2 or owned PG17.
Never uses an existing database, fixed password, external vendor, or installed service.
"""
from __future__ import annotations
import argparse
import json
from http_fixture import app_fixture


def scenarios(api, initial, empty_start=False):
    root_session, root_password = api.ready("identity-root", initial, "management")
    root = root_session["access_token"]
    if empty_start:
        assert api.call("GET", "/management/tenants", token=root)["items"] == []
        assert api.call("GET", "/management/units", token=root)["items"] == []
        api.checks.append("production profile starts without demo units or implicit tenant grants")
    catalog = api.call("GET", "/auth/permission-catalog", token=root)
    assert {"users:write", "units:write", "platform-users:write"} <= {row["key"] for row in catalog["items"] if row["delegable"]}
    assert catalog["password_policy"]["min_length"] >= 12
    unit_body = {"name": "正式验收单位甲"}
    unit = api.call("POST", "/management/units", unit_body, token=root, key="unit-a")
    same = api.call("POST", "/management/units", unit_body, token=root, key="unit-a")
    assert same["id"] == unit["id"] and unit["enabled"]
    api.call("POST", "/management/units", {**unit_body, "name": "different"}, token=root, key="unit-a", expected=409)
    other = api.call("POST", "/management/units", {"name": "正式验收单位乙"}, token=root)
    a, b = unit["id"], other["id"]
    assert a in {row["id"] for row in api.call("GET", "/management/tenants", token=root)["items"]}
    api.checks.append("formal unit creation, creator grant, non-revoking create exception and idempotency")

    admin_permissions = ["summary:read", "device:read", "alarm:read", "users:read", "users:write", "users:reset-password"]
    initial_admin = api.password()
    admin_row = api.call("POST", "/management/users", {"username": "unit-admin", "name": "单位管理员", "initial_password": initial_admin,
                         "permissions": admin_permissions}, token=root, tenant=a)
    admin_session, admin_password = api.ready("unit-admin", initial_admin, "customer")
    admin = admin_session["access_token"]
    customer_catalog = api.call("GET", "/auth/permission-catalog", token=admin)
    assert not any(row["delegable"] and "CUSTOMER" not in row["user_types"] for row in customer_catalog["items"])
    initial_reader = api.password()
    reader_body = {"username": "unit-reader", "name": "只读账户", "initial_password": initial_reader, "permissions": ["summary:read", "alarm:read"]}
    reader_row = api.call("POST", "/customer/users", reader_body, token=admin, key="create-reader")
    assert api.call("POST", "/customer/users", reader_body, token=admin, key="create-reader")["id"] == reader_row["id"]
    api.call("POST", "/customer/users", {**reader_body, "initial_password": api.password()}, token=admin, key="create-reader", expected=409)
    api.call("POST", "/customer/users", {**reader_body, "username": "escalation", "permissions": ["settings:write"]}, token=admin, expected=403)
    api.call("POST", "/customer/users", {**reader_body, "username": "spoof", "tenant_id": b}, token=admin, expected=400)
    api.call("GET", "/customer/users", token=admin, tenant=b, expected=403)
    api.call("GET", "/management/platform-users", token=admin, expected=403)
    reader_session, reader_password = api.ready("unit-reader", initial_reader, "customer")
    reader = reader_session["access_token"]
    api.call("GET", "/customer/users", token=reader, expected=403)
    api.checks.append("customer onboarding, mandatory password change, subset delegation and secret-safe replay conflict")

    another = api.call("POST", "/management/users", {"username": "other-reader", "name": "乙单位账户", "initial_password": api.password(), "permissions": ["alarm:read"]}, token=root, tenant=b)
    api.call("POST", f'/customer/users/{another["id"]}/reset-password', {"new_password": api.password(), "expected_revision": another["revision"]}, token=admin, expected=404)
    api.call("POST", f'/customer/users/{admin_row["id"]}/reset-password', {"new_password": api.password(), "expected_revision": 0}, token=admin, expected=409)
    rows = api.call("GET", "/customer/users", token=admin)["items"]
    reader_row = next(row for row in rows if row["id"] == reader_row["id"])
    replacement = api.password()
    reset_body = {"new_password": replacement, "expected_revision": reader_row["revision"]}
    reset_row = api.call("POST", f'/customer/users/{reader_row["id"]}/reset-password', reset_body, token=admin, key="reset-reader")
    assert reset_row["must_change_password"]
    api.call("POST", f'/customer/users/{reader_row["id"]}/reset-password', reset_body, token=admin, key="reset-reader")
    api.call("POST", f'/customer/users/{reader_row["id"]}/reset-password', {**reset_body, "new_password": api.password()}, token=admin, key="reset-reader", expected=409)
    api.call("GET", "/auth/me", token=reader, expected=401)
    api.call("POST", "/auth/login", {"username": "unit-reader", "password": reader_password, "audience": "customer"}, expected=401)
    reader_session, reader_password = api.ready("unit-reader", replacement, "customer")
    reader = reader_session["access_token"]
    api.checks.append("cross-tenant 404, self-management protection, admin reset and forced reauthentication")
    narrow_initial = api.password()
    api.call("POST", "/management/users", {"username": "narrow-resetter", "name": "受限凭据操作员", "initial_password": narrow_initial, "permissions": ["users:read", "users:reset-password"]}, token=root, tenant=a)
    narrow_session, narrow_password = api.ready("narrow-resetter", narrow_initial, "customer")
    admin_current = next(row for row in api.call("GET", "/customer/users", token=admin)["items"] if row["id"] == admin_row["id"])
    api.call("POST", f'/customer/users/{admin_row["id"]}/reset-password', {"new_password": api.password(), "expected_revision": admin_current["revision"]}, token=narrow_session["access_token"], expected=403)
    api.call("GET", "/auth/me", token=admin)
    api.checks.append("credential-reset permission cannot take over a higher-authority account")

    reader_row = next(row for row in api.call("GET", "/customer/users", token=admin)["items"] if row["username"] == "unit-reader")
    edit = {"name": reader_row["name"], "enabled": False, "permissions": reader_row["permissions"], "expected_revision": reader_row["revision"]}
    disabled = api.call("PUT", f'/customer/users/{reader_row["id"]}', edit, token=admin)
    api.call("GET", "/auth/me", token=reader, expected=401)
    api.call("PUT", f'/customer/users/{reader_row["id"]}', {**edit, "name": "stale"}, token=admin, expected=409)
    enabled = api.call("PUT", f'/customer/users/{reader_row["id"]}', {**edit, "enabled": True, "expected_revision": disabled["revision"]}, token=admin)
    assert enabled["enabled"]
    api.call("GET", "/auth/me", token=reader, expected=401)
    reader = api.login("unit-reader", reader_password, "customer")["access_token"]
    api.checks.append("disable/enable revokes sessions without reviving old tokens; optimistic revision enforced")

    platform_initial = api.password()
    platform_permissions = ["tenants:read", "platform-users:read", "platform-users:write", "users:read"]
    operator = api.call("POST", "/management/platform-users", {"username": "limited-platform", "name": "受限平台账户", "initial_password": platform_initial,
                        "permissions": platform_permissions, "tenant_ids": [a]}, token=root)
    op_session, op_password = api.ready("limited-platform", platform_initial, "management")
    op = op_session["access_token"]
    api.call("POST", "/management/platform-users", {"username": "outside-grant", "name": "不应创建", "initial_password": api.password(), "permissions": ["tenants:read"], "tenant_ids": [b]}, token=op, expected=403)
    wide = api.call("POST", "/management/platform-users", {"username": "wide-target", "name": "更宽授权目标", "initial_password": api.password(), "permissions": ["tenants:read"], "tenant_ids": [b]}, token=root)
    api.call("PUT", f'/management/platform-users/{wide["id"]}', {"name": wide["name"], "enabled": True, "permissions": ["tenants:read"], "tenant_ids": [], "expected_revision": wide["revision"]}, token=op, expected=403)
    root_row = next(row for row in api.call("GET", "/management/platform-users", token=root)["items"] if row["id"] == root_session["user"]["id"])
    assert root_row["is_protected"]
    root_edit = {"name": root_row["name"], "enabled": False, "permissions": root_row["permissions"], "tenant_ids": root_row["tenant_ids"], "expected_revision": root_row["revision"]}
    api.call("PUT", f'/management/platform-users/{root_row["id"]}', root_edit, token=root, expected=409)
    operator = next(row for row in api.call("GET", "/management/platform-users", token=root)["items"] if row["id"] == operator["id"])
    api.call("PUT", f'/management/platform-users/{operator["id"]}', {"name": operator["name"], "enabled": True, "permissions": platform_permissions, "tenant_ids": [], "expected_revision": operator["revision"]}, token=root)
    api.call("GET", "/auth/me", token=op, expected=401)
    op = api.login("limited-platform", op_password, "management")["access_token"]
    assert api.call("GET", "/management/tenants", token=op)["items"] == []
    api.call("GET", "/management/users", token=op, tenant=a, expected=403)
    api.checks.append("platform grant subset, protected root and immediate grant/session revocation")

    disabled_unit = api.call("PUT", f'/management/units/{a}', {"name": unit["name"], "enabled": False, "expected_revision": unit["revision"]}, token=root)
    api.call("GET", "/auth/me", token=admin, expected=401)
    api.call("POST", "/auth/login", {"username": "unit-admin", "password": admin_password, "audience": "customer"}, expected=401)
    api.call("GET", "/management/users", token=root, tenant=a, expected=403)
    assert a not in {row["id"] for row in api.call("GET", "/management/tenants", token=root)["items"]}
    assert a in {row["id"] for row in api.call("GET", "/management/units", token=root)["items"]}
    api.call("PUT", f'/management/units/{a}', {"name": unit["name"], "enabled": True, "expected_revision": disabled_unit["revision"]}, token=root)
    api.call("GET", "/auth/me", token=admin, expected=401)
    api.login("unit-admin", admin_password, "customer")
    api.checks.append("unit suspension blocks current sessions/login/scoped APIs; reenabling never resurrects tokens")
    api.call("POST", "/auth/logout", token=root, expected=204)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--storage", choices=("h2-dev", "postgresql"), default="h2-dev")
    args = parser.parse_args()
    with app_fixture(args.storage, username="identity-root", label="identity") as (api, initial, run):
        scenarios(api, initial, empty_start=args.storage == "postgresql")
        result = {"status": "passed", "storage": args.storage, "production_profile": args.storage == "postgresql", "checks": api.checks, "real_vendor_calls": 0}
        (run / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
