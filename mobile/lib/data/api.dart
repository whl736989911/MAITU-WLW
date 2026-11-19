import 'dart:math';

import '../core/api_client.dart';
import 'models.dart';

/// Generates a version-4 UUID for `Idempotency-Key` headers. Write endpoints
/// reuse the same key on retry; never mint a new key for a different body.
String newIdempotencyKey() {
  final random = Random.secure();
  final bytes = List<int>.generate(16, (_) => random.nextInt(256));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  final hex = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
}

const hikvisionDriverKey = 'HIKVISION_FIRECLOUD';
typedef RegistrationScope = ({String id, String name, List<Place> places});

/// Typed facade over the platform public API (customer audience).
///
/// Every path here is an existing backend route consumed by the web frontend;
/// no new endpoint is invented. Bodies use the backend's SNAKE_CASE keys and
/// omit fields the backend would reject as unknown.
class PlatformApi {
  PlatformApi(this._client);

  final ApiClient _client;

  // -- identity ------------------------------------------------------------

  Future<AuthResponse> login(String username, String password) async {
    final data = await _client.post(
      '/auth/login',
      public: true,
      body: {'username': username, 'password': password, 'audience': 'customer'},
    );
    final auth = AuthResponse.fromJson(_object(data));
    _validateCustomer(auth.user);
    if (auth.accessToken.isEmpty) throw _invalidReceipt;
    return auth;
  }

  Future<SessionView> me() async {
    final data = await _client.get('/auth/me');
    final view = SessionView.fromJson(_object(data));
    _validateCustomer(view.user);
    return view;
  }

  Future<void> changePassword(String currentPassword, String newPassword) async {
    await _client.post(
      '/auth/change-password',
      body: {'current_password': currentPassword, 'new_password': newPassword},
    );
  }

  Future<void> logout(String token) async {
    await _client.post('/auth/logout', bearer: token);
  }

  // -- summary -------------------------------------------------------------

  Future<Summary> summary() async {
    final data = await _client.get('/customer/summary');
    return Summary.fromJson(data as Map<String, dynamic>);
  }

  // -- devices -------------------------------------------------------------

  Future<Paginated<Device>> devices({
    int page = 1,
    int? size,
    String keyword = '',
    String? connectivity,
    String? physicalState,
    String? integrationState,
    String? systemType,
    String? deviceCategory,
  }) async {
    final query = <String, String>{'page': '$page', 'keyword': keyword};
    if (size != null) query['size'] = '$size';
    if (connectivity != null && connectivity.isNotEmpty) query['connectivity'] = connectivity;
    if (physicalState != null && physicalState.isNotEmpty) query['physical_state'] = physicalState;
    if (integrationState != null && integrationState.isNotEmpty) query['integration_state'] = integrationState;
    if (systemType != null && systemType.isNotEmpty) query['system_type'] = systemType;
    if (deviceCategory != null && deviceCategory.isNotEmpty) query['device_category'] = deviceCategory;
    final data = await _client.get('/customer/devices', query: query);
    return Paginated<Device>.fromJson(data as Map<String, dynamic>, Device.fromJson);
  }

  Future<DeviceDetail> device(String id) async {
    final data = await _client.get('/customer/devices/${Uri.encodeComponent(id)}');
    return DeviceDetail.fromJson(data as Map<String, dynamic>);
  }

  Future<List<String>> activeSourceDrivers(String id) async {
    final data = _object(await _client.get(
        '/customer/devices/${Uri.encodeComponent(id)}/source-bindings'));
    return _items(data)
        .map(_object)
        .where((item) => item['state'] == 'ACTIVE')
        .map((item) => item['driver_key'] as String)
        .toSet().toList();
  }

  // -- alarms --------------------------------------------------------------

  Future<Paginated<Alarm>> alarms({
    int page = 1,
    int? size,
    String? physicalState,
  }) async {
    final query = <String, String>{'page': '$page'};
    if (size != null) query['size'] = '$size';
    if (physicalState != null && physicalState.isNotEmpty) {
      query['physical_state'] = physicalState;
    }
    final data = await _client.get('/customer/alarms', query: query);
    return Paginated<Alarm>.fromJson(data as Map<String, dynamic>, Alarm.fromJson);
  }

  Future<Alarm> alarm(String id) async {
    final data = await _client.get('/customer/alarms/${Uri.encodeComponent(id)}');
    return _alarm(data, id);
  }

  Future<Paginated<AlarmTraceEntry>> alarmHistory(String id, {int page = 1, int? size}) async {
    final query = <String, String>{'page': '$page'};
    if (size != null) query['size'] = '$size';
    final data = await _client.get('/customer/alarms/${Uri.encodeComponent(id)}/history', query: query);
    return Paginated<AlarmTraceEntry>.fromJson(data as Map<String, dynamic>, AlarmTraceEntry.fromJson);
  }

  /// Submits a review. [remark] is limited to the platform's alarm remark
  /// length; [conclusion] is one of TRUE_FIRE | FALSE_ALARM | TEST | DEVICE_ALARM.
  Future<Alarm> reviewAlarm({
    required String id,
    required int expectedRevision,
    required String conclusion,
    required String remark,
    required String key,
  }) async {
    final data = await _client.post(
      '/customer/alarms/${Uri.encodeComponent(id)}/review',
      idempotencyKey: key,
      body: {
        'expected_revision': expectedRevision,
        'conclusion': conclusion,
        'remark': remark,
      },
    );
    final result = _alarm(data, id);
    if (result.revision != expectedRevision + 1 || result.responseState != 'HANDLED' ||
        result.conclusion != conclusion) throw _invalidReceipt;
    return result;
  }

  // -- places --------------------------------------------------------------

  Future<RegistrationScope> registrationScope() async {
    final data = await _client.get('/customer/scope-tree');
    if (data is! List || data.length != 1) throw _invalidReceipt;
    final unit = _object(data.single);
    final id = unit['id'];
    final name = unit['name'];
    final places = unit['places'];
    if (id is! String || id.isEmpty || name is! String || places is! List) {
      throw _invalidReceipt;
    }
    return (id: id, name: name, places: places.map((item) => Place.fromJson(_object(item)))
        .where((place) => place.id.isNotEmpty && place.tenantId == id).toList());
  }

  // -- device registration (Hikvision scan-add) ----------------------------

  Future<List<RegistrationDriver>> registrationDrivers() async {
    final data = await _client.get('/customer/device-registrations/drivers');
    final items = _items(data);
    return items.map((e) => RegistrationDriver.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<Paginated<RegistrationSummary>> registrations({int page = 1, int? size}) async {
    final query = <String, String>{'page': '$page'};
    if (size != null) query['size'] = '$size';
    final data = await _client.get('/customer/device-registrations', query: query);
    return Paginated<RegistrationSummary>.fromJson(data as Map<String, dynamic>, RegistrationSummary.fromJson);
  }

  /// Creates a vendor NEW_VENDOR draft. Returns the created detail; the draft
  /// is NOT yet submitted to the vendor cloud (cloud_state stays NOT_ATTEMPTED
  /// until a later reconcile/resume step).
  Future<RegistrationDetail> createRegistration({
    required String tenantId,
    required String key,
    required String externalSerial,
    required String deviceName,
    required String deviceModel,
    required int deviceType,
    required int communicationType,
    required String vendorUnitId,
    required String placeId,
    String? verificationCode,
    String? location,
  }) async {
    final body = <String, dynamic>{
      'mode': 'NEW_VENDOR',
      'driver_key': hikvisionDriverKey,
      'tenant_id': tenantId,
      'place_id': placeId,
      'external_serial': externalSerial,
      'device_name': deviceName,
      'device_model': deviceModel,
      'device_type': deviceType,
      'communication_type': communicationType,
      'vendor_unit_id': vendorUnitId,
    };
    if (verificationCode != null && verificationCode.isNotEmpty) body['verification_code'] = verificationCode;
    if (location != null && location.isNotEmpty) body['location'] = location;
    final data = await _client.post(
      '/customer/device-registrations',
      idempotencyKey: key,
      body: body,
    );
    final result = _registration(data);
    if (result.registration.externalSerial != externalSerial ||
        result.registration.placeId != placeId) throw _invalidReceipt;
    return result;
  }

  Future<RegistrationDetail> registrationDetail(String id) async {
    final data = await _client.get('/customer/device-registrations/${Uri.encodeComponent(id)}');
    return _registration(data, id: id);
  }

  Future<RegistrationDetail> resumeRegistration(String id) async {
    final data = await _client.post('/customer/device-registrations/${Uri.encodeComponent(id)}/resume');
    return _registration(data, id: id);
  }

  /// Read-only cloud lookup by serial. Does not create or delete any cloud
  /// record; unknown result is surfaced, not retried with a new body.
  Future<RegistrationDetail> reconcileRegistration(String id) async {
    final data = await _client.post('/customer/device-registrations/${Uri.encodeComponent(id)}/reconcile');
    return _registration(data, id: id);
  }

  /// Local commissioning: creates the local asset and binds the source. Does
  /// not change the terminal's physical state.
  Future<RegistrationDetail> commissionRegistration({
    required String id,
    required String tenantId,
    required String placeId,
    required String acceptanceNote,
    required String key,
    String? deviceName,
    String? address,
  }) async {
    final body = <String, dynamic>{
      'tenant_id': tenantId,
      'place_id': placeId,
      'acceptance_note': acceptanceNote,
    };
    if (deviceName != null && deviceName.isNotEmpty) body['device_name'] = deviceName;
    if (address != null && address.isNotEmpty) body['address'] = address;
    final data = await _client.post(
      '/customer/device-registrations/${Uri.encodeComponent(id)}/commission',
      idempotencyKey: key,
      body: body,
    );
    final result = _registration(data, id: id);
    if (result.registration.status != 'COMMISSIONED' ||
        (result.registration.deviceId ?? '').isEmpty) throw _invalidReceipt;
    return result;
  }
}

const _invalidReceipt = ApiError(0, 'INVALID_RESPONSE', '平台返回的记录无法确认');

Map<String, dynamic> _object(Object? data) =>
    data is Map<String, dynamic> ? data : throw _invalidReceipt;

List<Object?> _items(Object? data) {
  final items = _object(data)['items'];
  return items is List<Object?> ? items : throw _invalidReceipt;
}

void _validateCustomer(User user) {
  if (user.id.isEmpty || user.userType != 'CUSTOMER' || (user.tenantId ?? '').isEmpty) {
    throw _invalidReceipt;
  }
}

Alarm _alarm(Object? data, String id) {
  final map = _object(data);
  final revision = map['revision'];
  if (map['id'] != id || revision is! int || revision < 0 ||
      !const {'UNHANDLED', 'HANDLED'}.contains(map['response_state'])) {
    throw _invalidReceipt;
  }
  return Alarm.fromJson(map);
}

RegistrationDetail _registration(Object? data, {String? id}) {
  final detail = RegistrationDetail.fromJson(_object(data));
  final registration = detail.registration;
  if (registration.id.isEmpty || (id != null && registration.id != id) ||
      registration.driverKey != hikvisionDriverKey || registration.mode != 'NEW_VENDOR' ||
      registration.externalSerial.isEmpty || registration.cloudState == null ||
      registration.localState == null || registration.status.isEmpty) {
    throw _invalidReceipt;
  }
  return detail;
}
