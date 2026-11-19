-- Tenant-owned, versioned visual rule flows.
-- A flow holds a bounded acyclic graph over a reviewed node catalog. Publishing compiles the graph into
-- the already-existing typed consumers (alarm base configuration, alarm merge policy, notification rules)
-- inside one transaction and records their real ids/revisions; nothing else consumes this JSON.
-- Additive only: no flow is imported, enabled or activated by this migration and no permission is granted here.

CREATE TABLE rule_flows (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    name VARCHAR(120) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED')),
    definition_json TEXT NOT NULL,
    definition_sha256 VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    published_revision BIGINT,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    last_compile_ok BOOLEAN,
    last_compile_json TEXT,
    last_compile_at TIMESTAMP WITH TIME ZONE,
    created_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    archived_by VARCHAR(64) REFERENCES app_users(id),
    archived_at TIMESTAMP WITH TIME ZONE,
    archived_reason VARCHAR(1000),
    CONSTRAINT uq_rule_flows_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT uq_rule_flows_name UNIQUE (tenant_id, name),
    CHECK (enabled = FALSE OR published_revision IS NOT NULL),
    CHECK (status <> 'PUBLISHED' OR published_revision IS NOT NULL),
    CHECK (status <> 'ARCHIVED' OR (archived_by IS NOT NULL AND archived_at IS NOT NULL AND archived_reason IS NOT NULL)),
    CHECK (status = 'ARCHIVED' OR (archived_by IS NULL AND archived_at IS NULL AND archived_reason IS NULL))
);
CREATE INDEX idx_rule_flows_tenant ON rule_flows(tenant_id, status, updated_at);

-- Immutable published revision: the exact reviewed definition, its compile plan and the reason it was published.
-- definition_json is what the editor showed; plan_json is what the consumers were asked to become.
CREATE TABLE rule_flow_revisions (
    flow_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision >= 1),
    definition_json TEXT NOT NULL,
    definition_sha256 VARCHAR(64) NOT NULL,
    plan_hash VARCHAR(64) NOT NULL,
    plan_json TEXT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    published_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    published_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (flow_id, revision),
    CONSTRAINT fk_rule_flow_revisions_flow FOREIGN KEY (flow_id, tenant_id) REFERENCES rule_flows(id, tenant_id)
);
CREATE INDEX idx_rule_flow_revisions_tenant ON rule_flow_revisions(tenant_id, flow_id, published_at);

-- Real consumer identity/revision created or adopted by a flow revision. Mutable only through enable/disable,
-- which records the new revisions; the definition revision itself stays immutable.
CREATE TABLE rule_flow_consumer_bindings (
    flow_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    flow_revision BIGINT NOT NULL,
    consumer_kind VARCHAR(32) NOT NULL CHECK (consumer_kind IN ('ALARM_BASE_CONFIGURATION','ALARM_MERGE_POLICY','NOTIFICATION_RULE','RESOURCE_LINKAGE','OUTPUT_APPLICATION')),
    scope_key VARCHAR(64) NOT NULL,
    consumer_id VARCHAR(64) NOT NULL,
    consumer_revision BIGINT NOT NULL,
    owned_fields_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (flow_id, flow_revision, consumer_kind, scope_key),
    CONSTRAINT fk_rule_flow_bindings_revision FOREIGN KEY (flow_id, flow_revision) REFERENCES rule_flow_revisions(flow_id, revision)
);
CREATE INDEX idx_rule_flow_bindings_consumer ON rule_flow_consumer_bindings(tenant_id, consumer_kind, consumer_id);

-- Append-only record of every preview/publish/enable/disable attempt, including the per-node compile errors
-- that stopped it. A rejected compile never touches a consumer.
CREATE TABLE rule_flow_compile_reports (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    flow_id VARCHAR(64) NOT NULL,
    draft_revision BIGINT NOT NULL CHECK (draft_revision >= 0),
    plan_hash VARCHAR(64) NOT NULL,
    ok BOOLEAN NOT NULL,
    issues_json TEXT NOT NULL,
    created_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_rule_flow_reports_flow FOREIGN KEY (flow_id, tenant_id) REFERENCES rule_flows(id, tenant_id)
);
CREATE INDEX idx_rule_flow_reports_flow ON rule_flow_compile_reports(tenant_id, flow_id, created_at DESC);

CREATE TABLE rule_flow_events (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    flow_id VARCHAR(64) NOT NULL,
    action VARCHAR(32) NOT NULL,
    revision_before BIGINT,
    revision_after BIGINT NOT NULL,
    actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
    reason VARCHAR(1000) NOT NULL,
    detail_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_rule_flow_events_flow FOREIGN KEY (flow_id, tenant_id) REFERENCES rule_flows(id, tenant_id)
);
CREATE INDEX idx_rule_flow_events_flow ON rule_flow_events(tenant_id, flow_id, created_at DESC);
