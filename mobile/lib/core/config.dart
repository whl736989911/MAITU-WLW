import 'package:flutter/foundation.dart';

/// Runtime configuration.
///
/// The platform API base URL is supplied at build time and is intentionally
/// empty by default. Unlike a demo scaffold, an empty URL does NOT fall back to
/// mock data: the app refuses to start signed-in flows and shows a clear
/// configuration error. No public address, production credential or site
/// address is ever hardcoded.
class AppConfig {
  const AppConfig._();

  /// An HTTPS origin, or a loopback HTTP origin in debug builds only.
  static const String apiBaseUrl = String.fromEnvironment('API_BASE_URL');

  /// This app is the customer-side mobile client; the backend enforces the
  /// audience on every request and returns 401 when it mismatches the account.
  static const String audience = 'customer';

  static const String appName = '消防物联';
  static const String appDescription = '监测与处置平台';

  static bool get isConfigured => validOrigin(apiBaseUrl);

  static bool validOrigin(String value) {
    final uri = Uri.tryParse(value.trim());
    if (uri == null || !uri.hasAuthority || uri.host.isEmpty ||
        uri.userInfo.isNotEmpty || uri.hasQuery || uri.hasFragment ||
        (uri.path.isNotEmpty && uri.path != '/')) {
      return false;
    }
    return uri.scheme == 'https' ||
        (kDebugMode && uri.scheme == 'http' &&
            const {'localhost', '127.0.0.1', '::1', '10.0.2.2'}.contains(uri.host));
  }
}
