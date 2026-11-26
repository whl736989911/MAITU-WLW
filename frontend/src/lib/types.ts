export type Audience = 'management' | 'customer'
type CustomerPageName = 'customer-subsystem' | 'customer-places' | 'customer-contacts' | 'customer-controllers' | 'customer-roles' | 'customer-password' | 'customer-wechat' | 'customer-unit-parameters'
export type PageName = CustomerPageName | 'summary' | 'devices' | 'alarms' | 'settings' | 'units' | 'users' | 'platform-users' | 'roles' | 'products' | 'sources' | 'hikvision' | 'messages' | 'tasks' | 'parameters' | 'notification-contacts' | 'suppressions' | 'profiles' | 'bulk-io' | 'account' | 'my-profile' | 'unit-settings' | 'distribution' | 'wall' | 'audit-records' | 'device-groups' | 'legacy-ingest' | 'places' | 'relations' | 'notification-provider' | 'notification-resources' | 'notification-recharge-settings' | 'notification-recharge' | 'notification-deliveries' | 'duty-overview' | 'parameter-monitoring' | 'device-monitoring' | 'alarm-history' | 'fault-history' | 'operations-overview' | 'device-faults' | 'resource-linkage' | 'alarm-base-config' | 'maintenance-work-orders' | 'fire-archives' | 'alarm-sessions' | 'payment-settings' | 'device-services' | 'applications' | 'topology' | 'video-controllers' | 'rule-flows' | 'releases-migrations' | 'data-governance'
export interface User { id: string; username: string; name: string; user_type: 'PLATFORM' | 'CUSTOMER'; tenant_id: string | null; must_change_password: boolean }
export interface AuthResponse { access_token: string; user: User; permissions: string[] }
export interface Health { status: string; storage_mode: string; generic_ingest_enabled?: boolean; generic_message_worker_enabled?: boolean; roles: string[] }
export interface Tenant { id: string; name: string }
export interface DevicePoint { id: string; name: string; address: string; physical_state: string; latitude: string; longitude: string }
export interface DeviceDistribution { points: DevicePoint[]; with_coordinates: number; without_coordinates: number; semantics: string }

export interface OpsOverview {
  computed_at: string
  alarms: { unhandled: number; fire: number; oldest_unhandled_at: string | null }
  channel: { head: number | null; pending: number; last_recorded_at: string | null }
  tasks: { raw_message_pending: number; reparse_pending: number }
  sources: { enabled: number; last_received_at: string | null }
  semantics: string
}

export interface Summary { device_total: number | null; online: number | null; offline: number | null; unknown_connectivity: number | null; unactivated: number | null; today_alarms: number | null; unhandled_alarms: number | null; low_battery: number | null }
export interface BatteryLowThreshold { parameter_key: 'battery_low_threshold_percent'; metric_key: 'battery_percent'; unit: '%'; comparator: 'LT' | 'LTE'; threshold: number; threshold_json?: string }
export interface BatteryPolicy { parameter_key: 'battery_low_threshold_percent'; metric_key: 'battery_percent'; unit: '%'; status: 'NOT_CONFIGURED' | 'NO_DATA' | 'LOW' | 'NORMAL'; comparator: 'LT' | 'LTE' | null; threshold: number | null; threshold_json?: string; source_scope: 'NONE' | 'PRODUCT_VERSION' | 'DEVICE'; source_id: string | null; source_revision: number; evaluated_at: string }
export type DeviceStatusCode = 'OFFLINE' | 'FIRE' | 'DISABLED' | 'SHIELDED' | 'FAULT' | 'ONLINE' | 'UNACTIVATED' | 'UNKNOWN'
export interface DeviceStatusDisplay {
  online_status: 'ONLINE' | 'OFFLINE' | 'UNACTIVATED' | 'UNKNOWN'
  customer_status: DeviceStatusCode
  business_statuses: { code: 'FIRE' | 'DISABLED' | 'SHIELDED' | 'FAULT'; label: string }[]
}
export interface Device { id: string; tenant_id?: string; tenant_name?: string | null; place_name?: string | null; name: string | null; device_eui: string | null; model: string | null; source_model_code?: string | null; address: string | null; connectivity: string | null; physical_state: string | null; last_message_at: string | null; battery_percent: number | null; battery_percent_json?: string; is_simulated?: boolean; product_id?: string | null; product_version_id?: string | null; product_name?: string | null; version_number?: number | null; revision?: number; place_id?: string | null; system_type?: string | null; device_category?: string | null; integration_state?: 'CONNECTED' | 'PENDING_INTEGRATION' | 'LEGACY_UNVERIFIED' | 'RETIRED_SIMULATION'; legacy_integration_state?: string | null; offline_policy?: EffectiveParameter | null; battery_policy: BatteryPolicy; status_display?: DeviceStatusDisplay }
export interface MetricParameterDefinition { key: 'battery_low_threshold_percent'; metric_key: 'battery_percent'; name: string; unit: '%'; min: number; max: number; scale: 36; comparators: ('LT' | 'LTE')[]; allowed_scopes: ('PRODUCT_VERSION' | 'DEVICE')[]; consumer: 'BATTERY_OBSERVATION_ONLY'; effect: 'QUERY_ONLY' }
export type Conclusion = 'TRUE_FIRE' | 'FALSE_ALARM' | 'TEST' | 'DEVICE_ALARM'
export interface Alarm { id: string; tenant_id: string; device_id: string; device_eui?: string | null; device_name: string | null; address: string | null; event_type: string | null; occurred_at: string | null; physical_state: string | null; response_state: string | null; conclusion: string | null; revision: number; is_simulated?: boolean; time_quality?: 'SOURCE_REPORTED' | 'SOURCE_TIME_MISSING' | 'LEGACY_UNVERIFIED'; recorded_at?: string | null; latest_alarm_at?: string | null; recovered_at?: string | null }
export interface AlarmTraceState { physical_state: 'FIRE' | 'NORMAL'; response_state: 'UNHANDLED' | 'HANDLED'; conclusion: Conclusion | null }
export interface AlarmTraceEntry { id: string; alarm_id: string; action: 'FIRE_CREATED' | 'REVIEWED' | 'ALARM_RECOVERED' | 'DEVICE_RECOVERED' | 'MIGRATION_BASELINE'; revision_before: number | null; revision_after: number; before: AlarmTraceState | null; after: AlarmTraceState; occurred_at: string | null; recorded_at: string; actor_kind: 'PLATFORM' | 'CUSTOMER' | 'SYSTEM' | 'LEGACY_UNKNOWN'; actor_label: string; is_self: boolean; remark: string | null; origin: 'APPLICATION' | 'MIGRATION_SNAPSHOT'; actor_id?: string | null; actor_name?: string | null }
export interface AlarmCoverage { kind: 'FROM_CREATION' | 'SINCE_MIGRATION' | 'PARTIAL'; first_revision: number | null; journal_through_revision: number | null; current_revision: number; entry_total: number; baseline_recorded_at: string | null }
export interface AlarmHistory extends Paginated<AlarmTraceEntry> { coverage: AlarmCoverage }
export interface AlarmWorkbenchFilters { keyword: string; physical_state: 'FIRE' | 'NORMAL' | null; response_state: 'UNHANDLED' | 'HANDLED' | null; conclusion: Conclusion | 'UNREVIEWED' | null; occurred_from: string | null; occurred_to: string | null }
export interface AlarmWorkbenchMetrics { alarm_total: number; unhandled: number; handled: number; unrecovered: number; recovered: number; true_fire: number }
export interface AlarmMetricDefinitions { scope: 'FILTERED_ALL_PAGES'; time_field: 'ALARM_OCCURRED_AT'; time_bounds: 'FROM_INCLUSIVE_TO_EXCLUSIVE'; unhandled: 'RESPONSE_UNHANDLED'; unrecovered: 'PHYSICAL_FIRE'; true_fire: 'CURRENT_CONCLUSION_TRUE_FIRE' }
export interface AlarmWorkbench extends Paginated<Alarm> { as_of: string; timezone: 'Asia/Shanghai'; filters: AlarmWorkbenchFilters; metrics: AlarmWorkbenchMetrics; metric_definitions: AlarmMetricDefinitions }
export interface Setting { key: string; value: number; unit: string; min: number; max: number; description: string; revision: number }
export interface Paginated<T> { items: T[]; total: number; page: number; size: number }
export interface Unit { id: string; name: string; enabled: boolean; revision: number }
export type NotificationRechargeMode = 'ORGANIZATION' | 'GRANTS'
export interface NotificationRechargePolicy { mode: NotificationRechargeMode; revision: number; updated_at: string | null }
export interface NotificationRechargeGrant { user_id: string; user_name: string; username: string; owner_type: 'ORGANIZATION' | 'PLACE'; owner_id: string; owner_name: string; created_at: string; tenant_id?: string; tenant_name?: string }
export interface NotificationRechargeDestination { owner_type: 'ORGANIZATION' | 'PLACE'; owner_id: string; owner_name: string }
export interface NotificationRechargeOptions { policy: NotificationRechargePolicy; destinations: NotificationRechargeDestination[]; pricing: NotificationResourcePricing }
export type NotificationPaymentState = 'CREATED' | 'IN_FLIGHT' | 'ACCEPTED' | 'REJECTED' | 'UNKNOWN' | 'PAID' | 'CLOSED'
export interface NotificationCustomerOrder { id: string; channel: 'SMS' | 'VOICE'; quantity: number; amount_cents: number; status: 'PENDING' | 'PAID' | 'CANCELLED' | 'FAILED'; payment_state: NotificationPaymentState; code_url: string | null; expires_at: string | null; created_at: string; paid_at: string | null; close_state?: string | null }
export interface NotificationResourceAccount { id: string; owner_type: 'ORGANIZATION' | 'PLACE'; owner_id: string; owner_name: string; sms_total: number; sms_used: number; sms_remaining: number; voice_total: number; voice_used: number; voice_remaining: number; enabled: boolean; revision: number; updated_at: string | null; tenant_id?: string; tenant_name?: string }
export interface NotificationProviderAttempt { id: string; tenant_id: string; event_kind: 'FIRE' | 'FAULT'; source_kind: 'ALARM' | 'RAW_FAULT' | 'LEGACY_FAULT'; source_id: string; alarm_id: string | null; device_id: string; contact_id: string; channel: 'SMS' | 'VOICE'; attempt_no: number; status: 'PENDING' | 'SENT' | 'FAILED' | 'UNKNOWN' | 'SUPPRESSED' | 'RESOURCE_UNAVAILABLE'; provider_code: string | null; provider_id: string | null; created_at: string; finished_at: string | null }
export interface NotificationResourcePricing { sms_price_cents: number | null; voice_price_cents: number | null; revision: number; updated_at: string | null }
export interface NotificationResourceOrder { id: string; token: string; out_trade_no: string | null; tenant_id: string; account_id: string; channel: 'SMS' | 'VOICE'; quantity: number; amount_cents: number; status: 'PENDING' | 'PAID' | 'CANCELLED' | 'FAILED'; payment_state: string; code_url: string | null; created_by: string | null; created_at: string; paid_at: string | null }
export interface NotificationResourceLedger { id: string; account_id: string; channel: 'SMS' | 'VOICE'; kind: string; quantity: number; balance_after: number; source: string; reference: string | null; operator: string | null; created_at: string }
export interface NotificationCustomerAccount { account_id: string; owner_type: 'ORGANIZATION' | 'PLACE'; owner_id: string; owner_name: string; sms_total: number; sms_used: number; sms_remaining: number; voice_total: number; voice_used: number; voice_remaining: number; enabled: boolean; updated_at: string | null }
export interface NotificationCustomerLedger { id: string; account_id: string; channel: 'SMS' | 'VOICE'; kind: 'RECHARGE' | 'ADJUST' | 'CONSUME' | 'REFUND'; quantity: number; balance_after: number; created_at: string }
export interface NotificationChannelPolicySource { scope_type: 'UNIT' | 'PLACE'; scope_id: string; name: string }
export interface NotificationChannelPolicy { tenant_id: string; scope_type: 'UNIT' | 'PLACE'; place_id: string | null; configured: boolean; revision: number; sms_enabled: boolean | null; voice_enabled: boolean | null; wechat_enabled: boolean | null; effective_sms: boolean; effective_voice: boolean; effective_wechat: boolean; sms_source: NotificationChannelPolicySource | null; voice_source: NotificationChannelPolicySource | null; wechat_source: NotificationChannelPolicySource | null }
export interface NotificationProvider { sms_enabled: boolean; voice_enabled: boolean; sms_sign_name: string | null; sms_template_code: string | null; tts_code: string | null; tts_called_show_number: string | null; sms_endpoint: string; voice_endpoint: string; access_key_id_masked: string | null; secret_configured: boolean; test_mode_enabled: boolean; test_phone_masked: string | null; revision: number; updated_at: string | null }
export interface NotificationProviderStatus { real_enabled: boolean; sms_ready: boolean; voice_ready: boolean; secret_storage_ready: boolean; sms_enabled: boolean; voice_enabled: boolean; test_mode_enabled: boolean; test_phone_masked: string | null; revision: number }
export interface NotificationProviderTest { success: boolean; outcome: 'ACCEPTED' | 'REJECTED' | 'UNKNOWN'; channel: string; provider_id: string | null; code: string; message: string }
export interface PlaceNode { id: string; tenant_id: string; tenant_name: string; name: string; parent_id: string | null; legacy_place_id: string | null; device_count: number; level: number; revision: number }
export interface ScopePlaceNode extends PlaceNode { parent_context: {id:string|null;name:string}|null }
export interface ScopeUnit { id: string; name: string; places: ScopePlaceNode[] }
export interface ManagedUser { id: string; username: string; name: string; user_type: 'PLATFORM' | 'CUSTOMER'; tenant_id: string | null; tenant_name: string | null; enabled: boolean; must_change_password: boolean; revision: number; is_protected: boolean; permissions: string[]; tenant_ids: string[] }
export interface PermissionItem { key: string; label: string; user_types: ('PLATFORM' | 'CUSTOMER')[]; delegable: boolean }
export interface PermissionCatalog { items: PermissionItem[]; password_policy: { min_length: number; max_length: number } }
export interface UnitCreate { name: string }
export interface UnitUpdate extends UnitCreate { enabled: boolean; expected_revision: number }
export interface UserCreate { username: string; name: string; initial_password: string; permissions: string[]; tenant_ids?: string[] }
export interface UserUpdate { name: string; enabled: boolean; permissions: string[]; expected_revision: number; tenant_ids?: string[] }
export interface UserPasswordReset { new_password: string; expected_revision: number }
export interface Product { id: string; name: string; manufacturer: string; model: string; description: string; revision: number; state: 'ACTIVE' | 'ARCHIVED'; archived_at: string | null }
export type CapabilityState = 'SUPPORTED' | 'UNSUPPORTED' | 'PENDING'
export interface Capabilities { fire: CapabilityState; recovery: CapabilityState; battery: CapabilityState; fault?: CapabilityState; heartbeat?: CapabilityState; telemetry?: CapabilityState }
export type ProductEventType = 'FIRE' | 'FAULT' | 'RECOVERY' | 'HEARTBEAT' | 'PARAM' | 'OTHER'
export interface ProductReading { key: string; label: string; value_type: 'NUMBER' | 'STRING' | 'BOOLEAN'; value: number | string | boolean; unit: string | null }
export interface ExpectedMeasurement { key: string; state: 'VALID' | 'MISSING' | 'INVALID'; value?: number | string | boolean }
export interface ParserSample { name: string; case_kind: ProductEventType | 'UNKNOWN' | 'MALFORMED'; payload: string; expected_event: ProductEventType | 'UNKNOWN'; expected_battery_percent: number | null; expected_measurements?: ExpectedMeasurement[] }
export interface ParserTestRow { name: string; passed: boolean; event_type: string; battery_percent: number | null; readings: ProductReading[]; readings_json?: string; raw_event: unknown; raw_event_json?: string; diagnostics: string[] }
export interface ParserTestResult { version_id: string; revision: number; passed: boolean; results: ParserTestRow[] }
export interface ProductVersion { id: string; product_id: string; version_number: number; status: 'DRAFT' | 'PUBLISHED'; revision: number; schema: Record<string, unknown>; schema_json?: string; capabilities: Capabilities; battery_low_threshold: BatteryLowThreshold | null; parser_tested: boolean; published_at: string | null; parser_test_summary: { passed: boolean; tested_revision: number; schema_fingerprint?: string; samples?: ParserSample[]; results: ParserTestRow[] } | null; created_at: string }
export interface VersionHistory { id: string; from_version_id: string | null; to_version_id: string; reason: string; actor_id: string; occurred_at: string }
export interface DevicePlaceHistory { id: string; action: 'REGISTER' | 'MOVE'; from_place_id: string | null; from_place_name: string | null; to_place_id: string; to_place_name: string; revision_before: number | null; revision_after: number; actor_id: string; occurred_at: string }
export interface DeviceCreate { name: string; device_eui: string; address: string; place_id: string; product_version_id: string; system_type?: string | null; device_category?: string | null }
export interface IngestSource { id: string; tenant_id: string; name: string; protocol: 'GENERIC_HTTP_JSON_V1'; is_simulated?: boolean; enabled: boolean; revision: number; created_at: string; last_received_at: string | null; message_count: number; duplicate_count: number }
export interface CredentialResult { source: IngestSource; one_time_token: string | null; credential_recovery_required: boolean }
export interface SourceBinding { id: string; source_id: string; device_id: string; vendor_device_id: string; is_active: boolean; created_at: string; ended_at: string | null }
export type MessageApplicationState = 'NOT_APPLIED' | 'APPLIED' | 'STALE' | 'REJECTED'
export type CaptureOrigin = 'AUTHENTICATED_SOURCE' | 'LEGACY_UNVERIFIED'
export interface RawMessage { id: string; tenant_id: string; source_id: string; source_name: string; source_message_id: string; vendor_device_id: string; device_id: string | null; device_name: string | null; binding_id: string | null; product_version_id: string | null; received_at: string; reported_at: string | null; occurred_at: string | null; occurred_at_raw: string | null; payload_bytes: number; payload_sha256: string; binding_state: 'BOUND' | 'UNBOUND'; duplicate_count: number; revision: number; latest_preview_id: string | null; parse_status: 'NOT_PREVIEWED' | 'PREVIEWED'; business_status: 'NOT_APPLIED'; application_state: MessageApplicationState; applied_at: string | null; apply_error: string | null; capture_origin: CaptureOrigin }
export interface MessageRaw { id: string; payload: string; payload_bytes: number; payload_sha256: string }
export interface IngestPreview { id: string; raw_message_id: string; product_version_id: string; event_type: ProductEventType | 'UNKNOWN'; battery_percent: number | null; battery_percent_json?: string; readings: ProductReading[]; readings_json?: string; diagnostics: string[]; reported_at: string | null; time_quality: 'REPORTED' | 'MISSING' | 'INVALID'; raw_event?: unknown; raw_event_json?: string; created_at: string; actor_id: string; reason: string; business_status: 'NOT_APPLIED' }
export type PreviewSummary = Omit<IngestPreview, 'raw_event' | 'raw_event_json'>
export interface MyMessage { id: string; kind: 'SYSTEM' | 'ALARM'; title: string; body: string; status: 'UNREAD' | 'READ' | 'HANDLED'; created_at: string; read_at: string | null; handled_at: string | null; revision: number }
export interface MySession { created_at: string | null; expires_at: string; current: boolean }
export interface MyProfile { id: string; username: string; name: string; user_type: 'PLATFORM' | 'CUSTOMER'; tenant_id: string | null }

export interface ImportRow { row_no: number; name: string; device_eui: string; address: string; product_version_id: string; place_id: string | null; system_type?: string | null; device_category?: string | null; status: 'OK' | 'ERROR'; error: string | null }
export interface ImportPreview { source_sha256: string; rows: ImportRow[]; ok_count: number; error_count: number }
export interface ImportJob { id: string; status: 'CONFIRMED' | 'REJECTED'; row_count: number; ok_count: number; error_count: number; created_at: string }

export interface ProfileRule { id: string; tenant_id: string; version: number; window_days: number; min_samples: number; enabled: boolean; reason: string; created_at: string }
export interface ProfileSnapshot {
  id: string
  device_id: string
  rule_id: string
  rule_version: number
  window_days: number
  min_samples: number
  status: 'OK' | 'UNSUPPORTED' | 'MISSING' | 'INSUFFICIENT'
  sample_count: number
  latest_percent: string | null
  min_percent: string | null
  max_percent: string | null
  mean_percent: string | null
  window_start: string
  window_end: string
  computed_at: string
  reason: string
}


export interface SuppressionRecord {
  id: string
  tenant_id: string
  kind: 'NOTIFICATION'
  scope: 'TENANT' | 'DEVICES' | 'SENSORS'
  fire_confirmed: boolean
  reason: string
  starts_at: string
  ends_at: string
  status: 'ACTIVE' | 'CANCELLED'
  created_at: string
  cancelled_at: string | null
  cancel_reason: string | null
  revision: number
  enabled: boolean
  deleted: boolean
  effective_state: 'ACTIVE' | 'SCHEDULED' | 'DISABLED' | 'EXPIRED' | 'CANCELLED' | 'DELETED'
}

export interface ReparseJob {
  id: string
  tenant_id: string
  raw_message_id: string
  product_version_id: string
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  attempt_count: number
  max_attempts: number
  revision: number
  created_at: string
  started_at: string | null
  finished_at: string | null
  last_error_code: string | null
  result_json: string | null
}
export interface ReparseAttempt { id: string; attempt_no: number; worker_id: string; started_at: string; finished_at: string | null; outcome: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'EXPIRED'; error_code: string | null }
export interface ReparseDetail { job: ReparseJob; attempts: ReparseAttempt[] }

export interface MessageApplyResult {
  raw_message_id: string
  application_state: 'APPLIED' | 'STALE' | 'REJECTED'
  event_type: string | null
  alarm_id: string | null
  physical_state: string | null
  battery_percent: number | null
  battery_percent_json?: string
  readings: ProductReading[]
  readings_json?: string
  sequence: number | null
  occurred_at: string | null
  outbox_event_type: string | null
}

export interface MessageDetail { message: RawMessage; previews: PreviewSummary[] }
export type TaskStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'REJECTED' | 'CANCELLED'
export interface ProcessingTask { id: string; kind: string; tenant_id: string; raw_message_id: string; source_id: string; source_message_id: string; device_id: string; device_name: string | null; product_version_id: string; actor_id: string; expected_device_revision: number; recovery_alarm_id: string | null; reason: string; status: TaskStatus; revision: number; attempt_count: number; max_attempts: number; available_at: string; lease_owner: string | null; lease_until: string | null; lease_token: number; created_at: string; finished_at: string | null; last_error_code: string | null; result: string | null }
export interface ProcessingAttempt { id: string; attempt_no: number; lease_token: number; worker_id: string; started_at: string; finished_at: string | null; outcome: 'RUNNING' | 'SUCCEEDED' | 'REJECTED' | 'RETRY' | 'EXPIRED' | 'CANCELLED'; error_code: string | null }
export interface ProcessingDetail { task: ProcessingTask; attempts: ProcessingAttempt[] }
export type ParameterScope = 'TENANT' | 'PLACE' | 'GROUP' | 'DEVICE'
export interface ParameterDefinition { key: string; name: string; unit: string; min: number; max: number; value_type: 'INTEGER'; allowed_scopes: ('GLOBAL' | ParameterScope)[]; consumer: string; effect: 'QUERY_ONLY' | 'LOCAL_DERIVATION'; global_value: number; global_revision: number }
export interface EffectiveParameter { key: string; value: number; unit: string; source_scope: 'GLOBAL' | ParameterScope; source_id: string | null; source_revision: number; evaluated_at: string }
export interface ParameterChain { scope: 'GLOBAL' | ParameterScope; scope_id: string | null; has_override: boolean; value: number | null; revision: number; is_effective: boolean }
export interface ParameterPolicy { key: string; scope: ParameterScope; scope_id: string; tenant_id: string; has_override: boolean; value: number | null; revision: number; effective: EffectiveParameter; chain: ParameterChain[]; consumer: 'CONNECTIVITY_AND_OFFLINE_EVENTS' }
export interface ParameterChange { id: string; key: string; scope: ParameterScope; scope_id: string; tenant_id: string; action: 'SET' | 'CLEAR'; previous_value: number | null; value: number | null; revision_before: number; revision_after: number; actor_id: string; reason: string; created_at: string }
export interface ParameterMutation { policy: ParameterPolicy; change: ParameterChange }
export const pagePermission: Record<PageName, string> = {
  summary: 'summary:read', devices: 'device:read', alarms: 'alarm:read', settings: 'settings:read',
  units: 'units:read', places: 'units:read', relations: 'units:read',
  users: 'users:read', 'platform-users': 'platform-users:read', roles: 'roles:read',
  products: 'products:read', sources: 'sources:read', hikvision: 'integrations:read', messages: 'messages:read',
  tasks: 'tasks:read', parameters: 'parameters:read', 'legacy-ingest': 'legacy-ingest:read',
  'notification-provider': 'notification-config:read', 'notification-resources': 'notification-resources:read',
  'notification-recharge-settings': 'notification-resources:read', 'notification-recharge': 'notification-recharge:read',
  'notification-deliveries': 'notification-resources:read', 'notification-contacts': 'notification-config:read',
  suppressions: 'suppressions:read', profiles: 'profiles:read', 'bulk-io': 'imports:read', account: 'inbox:read',
  'my-profile': 'inbox:read', 'unit-settings': 'unit-parameters:read', distribution: 'device:read', wall: 'summary:read',
  'audit-records': 'audit:read', 'device-groups': 'device:read', 'duty-overview': 'summary:read',
  'parameter-monitoring': 'device:read', 'device-monitoring': 'device:read', 'alarm-history': 'alarm:read',
  'fault-history': 'device:read', 'operations-overview': 'summary:read', 'device-faults': 'device:read',
  'resource-linkage': 'resource-linkage:read', 'alarm-base-config': 'alarm-base:read', 'device-services': 'device-services:read',
  'maintenance-work-orders': 'maintenance:read', 'fire-archives': 'fire-archives:read',
  'alarm-sessions': 'alarm:read', 'payment-settings': 'payments:read',
  applications: 'applications:read', topology: 'topology:read', 'video-controllers': 'video:read',
  'rule-flows': 'rules:read', 'releases-migrations': 'platform-ops:read', 'data-governance': 'retention:read',
  'customer-subsystem': 'device:read',
  'customer-places': 'units:read', 'customer-contacts': 'unit-notifications:read', 'customer-controllers': 'controller:read',
  'customer-roles': 'roles:read', 'customer-password': '', 'customer-wechat': 'inbox:read',
  'customer-unit-parameters': 'unit-parameters:read',
}
export function allowedPage(page: PageName, audience: Audience, permissions: string[]) {
  if (page === 'customer-subsystem') return audience === 'customer' && (permissions.includes('device:read') || permissions.includes('video:read'))
  if (page === 'customer-password') return audience === 'customer'
  if (page.startsWith('customer-')) return audience === 'customer' && permissions.includes(pagePermission[page])
  if (page === 'my-profile') return audience === 'customer'
  if (page === 'unit-settings') return audience === 'customer' && permissions.some(permission => ['unit-parameters:read', 'unit-notifications:read', 'units:read', 'units:write'].includes(permission))
  if (page === 'hikvision') return audience === 'management' && permissions.includes('integrations:read')
  if (page === 'payment-settings') return audience === 'management' && permissions.includes('payments:read')
  if (page === 'tasks' || page === 'applications' || page === 'places' || page === 'relations') return false
  if (page === 'rule-flows' || page === 'releases-migrations' || page === 'data-governance') return audience === 'management' && permissions.includes(pagePermission[page])
  if (page === 'alarm-sessions') return permissions.includes('alarm:read') || (audience === 'management' && permissions.includes('alarm-base:read'))
  if (page === 'device-services') return permissions.includes('device:read') && permissions.includes('device-services:read')
  if (page === 'video-controllers') return permissions.includes('video:read') || (audience === 'management' && permissions.includes('controller:read'))
  if (page === 'resource-linkage' || page === 'alarm-base-config') return audience === 'management' && permissions.includes(pagePermission[page])
  if (page === 'roles') return audience === 'management' && permissions.includes('roles:read')
  if (page === 'suppressions') return audience === 'management' && permissions.includes('suppressions:read')
  if (page === 'profiles') return audience === 'management' && permissions.includes('profiles:read')
  if (page === 'bulk-io') return permissions.some(permission => ['imports:read', 'imports:write', 'exports:read', 'exports:write'].includes(permission))
  if (page === 'notification-deliveries') return audience === 'management' && permissions.includes('notification-resources:read')
  if (page === 'account') return permissions.includes('inbox:read')
  if (page === 'distribution') return audience === 'management' && permissions.includes('device:read')
  if (page === 'wall') return audience === 'management' && permissions.includes('summary:read')
  if (page === 'audit-records') return audience === 'management' && permissions.includes('audit:read')
  if (page === 'notification-recharge') return audience === 'customer' && permissions.includes('notification-recharge:read')
  if (audience === 'customer' && (page === 'settings' || page === 'units' || page === 'notification-provider' || page === 'notification-resources' || page === 'notification-recharge-settings' || page === 'platform-users' || page === 'products' || page === 'sources' || page === 'messages' || page === 'parameters' || page === 'notification-contacts')) return false
  return permissions.includes(pagePermission[page])
}
