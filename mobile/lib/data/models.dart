/// Typed models for the platform public API (customer audience).
///
/// Field names mirror the backend's SNAKE_CASE JSON exactly. Parsing is strict
/// about the scalar types the backend guarantees, while optional fields stay
/// null when absent; nothing is silently defaulted into a plausible value.
library;

// ---------------------------------------------------------------------------
// Shared decoding helpers
// ---------------------------------------------------------------------------

String? _str(Map<String, dynamic> m, String key) {
  final v = m[key];
  return v is String ? v : null;
}

int? _int(Map<String, dynamic> m, String key) {
  final v = m[key];
  return v is int ? v : null;
}

bool? _bool(Map<String, dynamic> m, String key) {
  final v = m[key];
  return v is bool ? v : null;
}

List<dynamic>? _list(Map<String, dynamic> m, String key) {
  final v = m[key];
  return v is List ? v : null;
}

Map<String, dynamic> _asMap(dynamic v) =>
    v is Map<String, dynamic> ? v : <String, dynamic>{};

// ---------------------------------------------------------------------------
// Identity
// ---------------------------------------------------------------------------

class User {
  const User({
    required this.id,
    required this.username,
    required this.name,
    required this.userType,
    required this.tenantId,
    required this.mustChangePassword,
  });

  final String id;
  final String username;
  final String name;
  final String userType; // PLATFORM | CUSTOMER
  final String? tenantId;
  final bool mustChangePassword;

  factory User.fromJson(Map<String, dynamic> m) => User(
        id: _str(m, 'id') ?? '',
        username: _str(m, 'username') ?? '',
        name: _str(m, 'name') ?? '',
        userType: _str(m, 'user_type') ?? '',
        tenantId: _str(m, 'tenant_id'),
        mustChangePassword: _bool(m, 'must_change_password') ?? false,
      );
}

class AuthResponse {
  const AuthResponse({
    required this.accessToken,
    required this.user,
    required this.permissions,
  });

  final String accessToken;
  final User user;
  final List<String> permissions;

  factory AuthResponse.fromJson(Map<String, dynamic> m) => AuthResponse(
        accessToken: _str(m, 'access_token') ?? '',
        user: User.fromJson(_asMap(m['user'])),
        permissions: (_list(m, 'permissions') ?? const []).whereType<String>().toList(),
      );
}

class SessionView {
  const SessionView({required this.user, required this.permissions});

  final User user;
  final List<String> permissions;

  factory SessionView.fromJson(Map<String, dynamic> m) => SessionView(
        user: User.fromJson(_asMap(m['user'])),
        permissions: (_list(m, 'permissions') ?? const []).whereType<String>().toList(),
      );
}

// ---------------------------------------------------------------------------
// Dashboard summary
// ---------------------------------------------------------------------------

class Summary {
  const Summary({
    required this.deviceTotal,
    required this.online,
    required this.offline,
    required this.unactivated,
    required this.unknownConnectivity,
    required this.todayAlarms,
    required this.unhandledAlarms,
    required this.lowBattery,
  });

  final int? deviceTotal;
  final int? online;
  final int? offline;
  final int? unactivated;
  final int? unknownConnectivity;
  final int? todayAlarms;
  final int? unhandledAlarms;
  final int? lowBattery;

  factory Summary.fromJson(Map<String, dynamic> m) => Summary(
        deviceTotal: _int(m, 'device_total'),
        online: _int(m, 'online'),
        offline: _int(m, 'offline'),
        unactivated: _int(m, 'unactivated'),
        unknownConnectivity: _int(m, 'unknown_connectivity'),
        todayAlarms: _int(m, 'today_alarms'),
        unhandledAlarms: _int(m, 'unhandled_alarms'),
        lowBattery: _int(m, 'low_battery'),
      );
}

// ---------------------------------------------------------------------------
// Devices
// ---------------------------------------------------------------------------

class DeviceBusinessStatus {
  const DeviceBusinessStatus({required this.code, required this.label});

  final String code;
  final String label;

  factory DeviceBusinessStatus.fromJson(Map<String, dynamic> m) =>
      DeviceBusinessStatus(code: _str(m, 'code') ?? '', label: _str(m, 'label') ?? '');
}

class DeviceStatusDisplay {
  const DeviceStatusDisplay({
    required this.onlineStatus,
    required this.customerStatus,
    required this.businessStatuses,
  });

  final String onlineStatus; // ONLINE | OFFLINE | UNACTIVATED | UNKNOWN
  final String customerStatus; // OFFLINE | FIRE | DISABLED | SHIELDED | FAULT | ONLINE | UNACTIVATED | UNKNOWN
  final List<DeviceBusinessStatus> businessStatuses;

  factory DeviceStatusDisplay.fromJson(Map<String, dynamic> m) =>
      DeviceStatusDisplay(
        onlineStatus: _str(m, 'online_status') ?? '',
        customerStatus: _str(m, 'customer_status') ?? '',
        businessStatuses: (_list(m, 'business_statuses') ?? const [])
            .map((e) => DeviceBusinessStatus.fromJson(_asMap(e)))
            .toList(),
      );
}

class Device {
  const Device({
    required this.id,
    this.tenantId,
    this.tenantName,
    this.placeName,
    required this.name,
    this.deviceEui,
    this.model,
    this.address,
    this.connectivity,
    this.physicalState,
    this.lastMessageAt,
    this.batteryPercent,
    this.isSimulated,
    this.productId,
    this.productVersionId,
    this.productName,
    this.versionNumber,
    this.revision,
    this.integrationState,
    this.placeId,
    this.systemType,
    this.deviceCategory,
    this.statusDisplay,
  });

  final String id;
  final String? tenantId;
  final String? tenantName;
  final String? placeName;
  final String? name;
  final String? deviceEui;
  final String? model;
  final String? address;
  final String? connectivity;
  final String? physicalState;
  final String? lastMessageAt;
  final num? batteryPercent;
  final bool? isSimulated;
  final String? productId;
  final String? productVersionId;
  final String? productName;
  final int? versionNumber;
  final int? revision;
  final String? integrationState;
  final String? placeId;
  final String? systemType;
  final String? deviceCategory;
  final DeviceStatusDisplay? statusDisplay;

  factory Device.fromJson(Map<String, dynamic> m) {
    final rawStatus = m['status_display'];
    return Device(
      id: _str(m, 'id') ?? '',
      tenantId: _str(m, 'tenant_id'),
      tenantName: _str(m, 'tenant_name'),
      placeName: _str(m, 'place_name'),
      name: _str(m, 'name'),
      deviceEui: _str(m, 'device_eui'),
      model: _str(m, 'model'),
      address: _str(m, 'address'),
      connectivity: _str(m, 'connectivity'),
      physicalState: _str(m, 'physical_state'),
      lastMessageAt: _str(m, 'last_message_at'),
      batteryPercent: m['battery_percent'] is num ? m['battery_percent'] as num : null,
      isSimulated: _bool(m, 'is_simulated'),
      productId: _str(m, 'product_id'),
      productVersionId: _str(m, 'product_version_id'),
      productName: _str(m, 'product_name'),
      versionNumber: _int(m, 'version_number'),
      revision: _int(m, 'revision'),
      integrationState: _str(m, 'integration_state'),
      placeId: _str(m, 'place_id'),
      systemType: _str(m, 'system_type'),
      deviceCategory: _str(m, 'device_category'),
      statusDisplay: rawStatus is Map<String, dynamic>
          ? DeviceStatusDisplay.fromJson(rawStatus)
          : null,
    );
  }
}

class DeviceDetail {
  const DeviceDetail({required this.asOf, required this.timezone, required this.consumer, required this.device});

  final String? asOf;
  final String? timezone;
  final String? consumer;
  final Device device;

  factory DeviceDetail.fromJson(Map<String, dynamic> m) => DeviceDetail(
        asOf: _str(m, 'as_of'),
        timezone: _str(m, 'timezone'),
        consumer: _str(m, 'consumer'),
        device: Device.fromJson(_asMap(m['device'])),
      );
}

// ---------------------------------------------------------------------------
// Alarms
// ---------------------------------------------------------------------------

class Alarm {
  const Alarm({
    required this.id,
    required this.tenantId,
    required this.deviceId,
    this.deviceName,
    this.address,
    this.eventType,
    this.occurredAt,
    this.physicalState,
    this.responseState,
    this.conclusion,
    required this.revision,
    this.isSimulated,
    this.timeQuality,
    this.recordedAt,
  });

  final String id;
  final String tenantId;
  final String deviceId;
  final String? deviceName;
  final String? address;
  final String? eventType;
  final String? occurredAt;
  final String? physicalState;
  final String? responseState;
  final String? conclusion;
  final int revision;
  final bool? isSimulated;
  final String? timeQuality;
  final String? recordedAt;

  bool get isUnhandled => responseState == 'UNHANDLED';
  bool get isFire => physicalState == 'FIRE';

  factory Alarm.fromJson(Map<String, dynamic> m) => Alarm(
        id: _str(m, 'id') ?? '',
        tenantId: _str(m, 'tenant_id') ?? '',
        deviceId: _str(m, 'device_id') ?? '',
        deviceName: _str(m, 'device_name'),
        address: _str(m, 'address'),
        eventType: _str(m, 'event_type'),
        occurredAt: _str(m, 'occurred_at'),
        physicalState: _str(m, 'physical_state'),
        responseState: _str(m, 'response_state'),
        conclusion: _str(m, 'conclusion'),
        revision: _int(m, 'revision') ?? 0,
        isSimulated: _bool(m, 'is_simulated'),
        timeQuality: _str(m, 'time_quality'),
        recordedAt: _str(m, 'recorded_at'),
      );
}

class AlarmTraceState {
  const AlarmTraceState({required this.physicalState, required this.responseState, this.conclusion});

  final String? physicalState;
  final String? responseState;
  final String? conclusion;

  factory AlarmTraceState.fromJson(Map<String, dynamic> m) => AlarmTraceState(
        physicalState: _str(m, 'physical_state'),
        responseState: _str(m, 'response_state'),
        conclusion: _str(m, 'conclusion'),
      );
}

class AlarmTraceEntry {
  const AlarmTraceEntry({
    required this.id,
    required this.alarmId,
    required this.action,
    this.revisionBefore,
    required this.revisionAfter,
    required this.occurredAt,
    required this.recordedAt,
    required this.actorKind,
    required this.actorLabel,
    required this.isSelf,
    this.remark,
    required this.origin,
    required this.before,
    required this.after,
  });

  final String id;
  final String alarmId;
  final String action;
  final int? revisionBefore;
  final int revisionAfter;
  final String? occurredAt;
  final String? recordedAt;
  final String actorKind;
  final String actorLabel;
  final bool isSelf;
  final String? remark;
  final String origin;
  final AlarmTraceState? before;
  final AlarmTraceState after;

  factory AlarmTraceEntry.fromJson(Map<String, dynamic> m) => AlarmTraceEntry(
        id: _str(m, 'id') ?? '',
        alarmId: _str(m, 'alarm_id') ?? '',
        action: _str(m, 'action') ?? '',
        revisionBefore: _int(m, 'revision_before'),
        revisionAfter: _int(m, 'revision_after') ?? 0,
        occurredAt: _str(m, 'occurred_at'),
        recordedAt: _str(m, 'recorded_at'),
        actorKind: _str(m, 'actor_kind') ?? '',
        actorLabel: _str(m, 'actor_label') ?? '',
        isSelf: _bool(m, 'is_self') ?? false,
        remark: _str(m, 'remark'),
        origin: _str(m, 'origin') ?? '',
        before: m['before'] is Map<String, dynamic>
            ? AlarmTraceState.fromJson(_asMap(m['before']))
            : null,
        after: AlarmTraceState.fromJson(_asMap(m['after'])),
      );
}

// ---------------------------------------------------------------------------
// Places
// ---------------------------------------------------------------------------

class Place {
  const Place({
    required this.id,
    this.tenantId,
    this.tenantName,
    required this.name,
    this.parentId,
    this.legacyPlaceId,
    required this.deviceCount,
    required this.level,
    required this.revision,
  });

  final String id;
  final String? tenantId;
  final String? tenantName;
  final String name;
  final String? parentId;
  final String? legacyPlaceId;
  final int deviceCount;
  final int level;
  final int revision;

  factory Place.fromJson(Map<String, dynamic> m) => Place(
        id: _str(m, 'id') ?? '',
        tenantId: _str(m, 'tenant_id'),
        tenantName: _str(m, 'tenant_name'),
        name: _str(m, 'name') ?? '',
        parentId: _str(m, 'parent_id') ?? _str(m, 'parent_place_id'),
        legacyPlaceId: _str(m, 'legacy_place_id'),
        deviceCount: _int(m, 'device_count') ?? 0,
        level: _int(m, 'level') ?? 0,
        revision: _int(m, 'revision') ?? 0,
      );
}

// ---------------------------------------------------------------------------
// Device registration (vendor onboarding / Hikvision scan-add)
// ---------------------------------------------------------------------------

class RegistrationDriver {
  const RegistrationDriver({
    required this.key,
    required this.mode,
    required this.displayName,
    required this.configured,
    required this.enabled,
    required this.legacyOriginAccount,
    required this.importedLedgerAssociation,
  });

  final String key;
  final String mode;
  final String displayName;
  final bool configured;
  final bool enabled;
  final bool legacyOriginAccount;
  final bool importedLedgerAssociation;

  factory RegistrationDriver.fromJson(Map<String, dynamic> m) => RegistrationDriver(
        key: _str(m, 'key') ?? '',
        mode: _str(m, 'mode') ?? '',
        displayName: _str(m, 'display_name') ?? '',
        configured: _bool(m, 'configured') ?? false,
        enabled: _bool(m, 'enabled') ?? false,
        legacyOriginAccount: _bool(m, 'legacy_origin_account') ?? false,
        importedLedgerAssociation: _bool(m, 'imported_ledger_association') ?? false,
      );
}

class RegistrationSummary {
  const RegistrationSummary({
    required this.id,
    required this.driverKey,
    required this.mode,
    required this.status,
    required this.revision,
    this.deviceId,
    required this.externalSerial,
    this.deviceName,
    this.cloudState,
    this.localState,
    this.placeId,
    this.productVersionId,
    this.createdAt,
    this.updatedAt,
    this.commissionedAt,
  });

  final String id;
  final String driverKey;
  final String mode;
  final String status;
  final int revision;
  final String? deviceId;
  final String externalSerial;
  final String? deviceName;
  final String? cloudState;
  final String? localState;
  final String? placeId;
  final String? productVersionId;
  final String? createdAt;
  final String? updatedAt;
  final String? commissionedAt;

  factory RegistrationSummary.fromJson(Map<String, dynamic> m) => RegistrationSummary(
        id: _str(m, 'id') ?? '',
        driverKey: _str(m, 'driver_key') ?? '',
        mode: _str(m, 'mode') ?? '',
        status: _str(m, 'status') ?? '',
        revision: _int(m, 'revision') ?? 0,
        deviceId: _str(m, 'device_id'),
        externalSerial: _str(m, 'external_serial') ?? '',
        deviceName: _str(m, 'device_name'),
        cloudState: _str(m, 'cloud_state'),
        localState: _str(m, 'local_state'),
        placeId: _str(m, 'place_id'),
        productVersionId: _str(m, 'product_version_id'),
        createdAt: _str(m, 'created_at'),
        updatedAt: _str(m, 'updated_at'),
        commissionedAt: _str(m, 'commissioned_at'),
      );
}

class RegistrationEvent {
  const RegistrationEvent({required this.step, required this.outcome, required this.occurredAt});

  final String step;
  final String outcome;
  final String? occurredAt;

  factory RegistrationEvent.fromJson(Map<String, dynamic> m) => RegistrationEvent(
        step: _str(m, 'step') ?? '',
        outcome: _str(m, 'outcome') ?? '',
        occurredAt: _str(m, 'occurred_at'),
      );
}

class RegistrationDetail {
  const RegistrationDetail({
    required this.registration,
    this.deviceModel,
    this.location,
    this.acceptanceNote,
    required this.vendorPayloadStored,
    required this.events,
  });

  final RegistrationSummary registration;
  final String? deviceModel;
  final String? location;
  final String? acceptanceNote;
  final bool vendorPayloadStored;
  final List<RegistrationEvent> events;

  factory RegistrationDetail.fromJson(Map<String, dynamic> m) => RegistrationDetail(
        registration: RegistrationSummary.fromJson(_asMap(m['registration'])),
        deviceModel: _str(m, 'device_model'),
        location: _str(m, 'location'),
        acceptanceNote: _str(m, 'acceptance_note'),
        vendorPayloadStored: _bool(m, 'vendor_payload_stored') ?? false,
        events: (_list(m, 'events') ?? const [])
            .map((e) => RegistrationEvent.fromJson(_asMap(e)))
            .toList(),
      );
}

// ---------------------------------------------------------------------------
// Pagination
// ---------------------------------------------------------------------------

class Paginated<T> {
  const Paginated({required this.items, required this.total, required this.page, required this.size});

  final List<T> items;
  final int total;
  final int page;
  final int size;

  bool get hasMore => page * size < total;

  factory Paginated.fromJson(
    Map<String, dynamic> m,
    T Function(Map<String, dynamic>) itemParser,
  ) =>
      Paginated<T>(
        items: (_list(m, 'items') ?? const [])
            .map((e) => itemParser(_asMap(e)))
            .toList(),
        total: _int(m, 'total') ?? 0,
        page: _int(m, 'page') ?? 1,
        size: _int(m, 'size') ?? 0,
      );
}
