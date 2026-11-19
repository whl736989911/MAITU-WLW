import 'dart:convert';

import 'package:http/http.dart' as http;

import 'config.dart';

/// A parsed, typed error from the platform API. The backend returns
/// `{"code": ..., "message": ..., "request_id": ...}` on failure, with the
/// request id also available on the `X-Request-Id` response header.
class ApiError implements Exception {
  const ApiError(this.status, this.code, this.message, [this.requestId]);

  final int status;
  final String code;
  final String message;
  final String? requestId;

  bool get isUnauthorized => status == 401;
  bool get isConflict => status == 409;

  /// Loss of an answer (network cut, backend restart, connection reset). Never
  /// treat this as a definitive failure: the caller must not retry a write with
  /// a different idempotency key or body.
  bool get isUnknown => status == 0 || status >= 500 || code == 'INVALID_RESPONSE';

  @override
  String toString() =>
      '$message（$code${requestId != null && requestId!.isNotEmpty ? ' · 请求编号 $requestId' : ' · 请求编号未提供'}）';
}

/// Thin typed HTTP client for the platform public API.
///
/// It carries the bearer token, sets `Accept`/`Content-Type`, and attaches an
/// `Idempotency-Key` header for the write endpoints that require one. All
/// failures are raised as [ApiError]; a non-JSON or truncated body is never
/// silently accepted.
class ApiClient {
  ApiClient({String? baseUrl, http.Client? client})
      : _transport = client ?? http.Client(),
        baseUrl = (baseUrl ?? AppConfig.apiBaseUrl).trim().replaceFirst(RegExp(r'/$'), '');

  final String baseUrl;
  final http.Client _transport;
  String? token;

  /// Invoked when a non-public request made with [token] is rejected with 401.
  /// The token actually used is supplied so the owner can ignore a stale
  /// response from a session that has since been replaced.
  void Function(String usedToken)? onUnauthorized;

  void close() => _transport.close();

  Uri _uri(String path, [Map<String, String>? query]) {
    final uri = Uri.parse('$baseUrl/api$path');
    return query == null || query.isEmpty
        ? uri
        : uri.replace(queryParameters: query);
  }

  Future<dynamic> get(String path, {Map<String, String>? query}) =>
      _request('GET', path, query: query, bearer: token);
  Future<dynamic> post(
    String path, {
    Object? body,
    String? idempotencyKey,
    bool public = false,
    String? bearer,
  }) =>
      _request('POST', path, body: body, idempotencyKey: idempotencyKey,
          bearer: public ? null : bearer ?? token);

  Future<dynamic> _request(
    String method,
    String path, {
    Map<String, String>? query,
    Object? body,
    String? idempotencyKey,
    String? bearer,
  }) {
    if (!AppConfig.validOrigin(baseUrl)) {
      throw const ApiError(400, 'INVALID_ORIGIN', '平台地址必须为 HTTPS，调试版仅允许回环 HTTP');
    }
    final request = http.Request(method, _uri(path, query))
      ..followRedirects = false
      ..headers['Accept'] = 'application/json';
    if (bearer != null && bearer.isNotEmpty) {
      request.headers['Authorization'] = 'Bearer $bearer';
    }
    if (idempotencyKey != null) request.headers['Idempotency-Key'] = idempotencyKey;
    if (body != null) {
      request.headers['Content-Type'] = 'application/json; charset=utf-8';
      request.bodyBytes = utf8.encode(jsonEncode(body));
    }
    return _send(bearer, () async =>
        http.Response.fromStream(await _transport.send(request)));
  }

  Future<dynamic> _send(
    String? usedToken,
    Future<http.Response> Function() perform,
  ) async {
    http.Response response;
    try {
      response = await perform().timeout(const Duration(seconds: 30));
    } on Exception {
      throw const ApiError(0, 'NETWORK_ERROR', '未收到服务端确认，请检查网络');
    }

    if (response.statusCode == 401 && usedToken != null) {
      onUnauthorized?.call(usedToken);
    }
    Object? payload;
    if (response.bodyBytes.isNotEmpty) {
      try {
        payload = jsonDecode(utf8.decode(response.bodyBytes));
      } on FormatException {
        throw ApiError(response.statusCode, 'INVALID_RESPONSE',
            '服务端返回了无法确认的响应', response.headers['x-request-id']);
      }
    }

    if (response.statusCode >= 200 && response.statusCode < 300) {
      return payload;
    }


    final map = payload is Map<String, dynamic> ? payload : const <String, dynamic>{};
    throw ApiError(
      response.statusCode,
      map['code'] is String ? map['code'] as String : 'HTTP_${response.statusCode}',
      map['message'] is String ? map['message'] as String : '请求失败（HTTP ${response.statusCode}）',
      map['request_id'] is String
          ? map['request_id'] as String
          : response.headers['x-request-id'],
    );
  }
}
