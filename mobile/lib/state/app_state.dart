import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../core/api_client.dart';
import '../data/api.dart';
import '../data/models.dart';

/// Persistence boundary for the session token. Abstracted so tests can use an
/// in-memory store without platform channels.
abstract class TokenStore {
  Future<String?> read();
  Future<void> write(String token);
  Future<void> delete();
}

class SecureTokenStore implements TokenStore {
  SecureTokenStore({FlutterSecureStorage? storage})
      : _storage = storage ?? const FlutterSecureStorage(
          aOptions: AndroidOptions(encryptedSharedPreferences: true),
        );

  static const _key = 'session_token';
  final FlutterSecureStorage _storage;

  @override
  Future<String?> read() => _storage.read(key: _key);

  @override
  Future<void> write(String token) => _storage.write(key: _key, value: token);

  @override
  Future<void> delete() => _storage.delete(key: _key);
}

/// Session + identity state for the whole app.
///
/// The bearer token is the only persistent credential; the live session is
/// revalidated against `/auth/me` on restore and the app never fabricates a
/// signed-in state from a stale token. A 401 for the current token clears it.
class AppState extends ChangeNotifier {
  AppState({String? baseUrl, TokenStore? tokenStore})
      : _tokenStore = tokenStore ?? SecureTokenStore(),
        _client = ApiClient(baseUrl: baseUrl) {
    _api = PlatformApi(_client);
    _client.onUnauthorized = (usedToken) {
      // Only a 401 for the token we are still using clears the session; a late
      // response from an older session must never drop a newer login.
      if (usedToken.isNotEmpty && usedToken == _client.token) {
        _expireSession();
      }
    };
  }

  final TokenStore _tokenStore;
  final ApiClient _client;
  late final PlatformApi _api;

  PlatformApi get api => _api;

  User? _user;
  List<String> _permissions = const [];
  bool _restoring = false;
  bool _authenticating = false;
  String? _error;

  User? get user => _user;
  List<String> get permissions => _permissions;
  bool get signedIn => _user != null;
  bool get mustChangePassword => _user?.mustChangePassword ?? false;
  bool get restoring => _restoring;
  bool get authenticating => _authenticating;
  String? get error => _error;

  /// Incremented on every credential change; guards against stale async
  /// responses overwriting a newer session.
  int _generation = 0;
  int get generation => _generation;

  bool hasPermission(String permission) => _permissions.contains(permission);

  bool get canRegisterHikvision => hasPermission('device:write') &&
      hasPermission('device-registrations:write') && hasPermission('units:read');

  /// Loads the persisted token and revalidates it. If the token is absent or
  /// rejected, the app stays signed out (no fabricated state).
  Future<void> restore() async {
    _restoring = true;
    notifyListeners();
    try {
      final token = await _tokenStore.read();
      if (token == null || token.isEmpty || _disposed) return;
      _client.token = token;
      final view = await _api.me();
      if (_disposed || token != _client.token) return;
      _user = view.user;
      _permissions = view.permissions;
      notifyListeners();
    } on ApiError catch (e) {
      _client.token = null;
      if (e.isUnauthorized) await _forgetToken();
      if (!_disposed) _error = e.toString();
    } on Exception {
      if (!_disposed) _error = '无法读取本机登录信息，请重新登录';
    } finally {
      _restoring = false;
      if (!_disposed) notifyListeners();
    }
  }

  Future<bool> login(String username, String password) async {
    if (_authenticating || _disposed) return false;
    _authenticating = true;
    _error = null;
    notifyListeners();
    final gen = _generation;
    try {
      final response = await _api.login(username, password);
      if (_disposed || gen != _generation) return false;
      await _deletingToken;
      await _persistToken(response.accessToken);
      if (_disposed || gen != _generation) return false;
      _generation++;
      _client.token = response.accessToken;
      _user = response.user;
      _permissions = List.unmodifiable(response.permissions);
      return true;
    } on ApiError catch (e) {
      if (!_disposed && gen == _generation) {
        _error = e.toString();
        notifyListeners();
      }
      return false;
    } finally {
      _authenticating = false;
      if (!_disposed) notifyListeners();
    }
  }

  Future<void> logout() async {
    final token = _client.token;
    final gen = ++_generation;
    _client.token = null;
    _user = null;
    _permissions = const [];
    _error = null;
    _restoring = true;
    notifyListeners();
    try {
      final forgotten = await _forgetToken();
      if (token != null) await _api.logout(token);
      if (!forgotten && !_disposed && gen == _generation) {
        _error = '服务器已退出；未能清除本机旧凭据';
      }
    } on ApiError catch (e) {
      if (!_disposed && gen == _generation && !e.isUnauthorized) {
        _error = '已退出当前界面，服务器退出未确认。$e';
      }
    } finally {
      if (!_disposed && gen == _generation) {
        _restoring = false;
        notifyListeners();
      }
    }
  }

  Future<void> _persistToken(String token) async {
    try {
      await _tokenStore.write(token);
    } on Exception {
      await _forgetToken();
    }
  }

  Future<bool> _forgetToken() async {
    try {
      await _tokenStore.delete();
      return true;
    } on Exception {
      return false;
    }
  }

  Future<bool>? _deletingToken;

  void _expireSession({String message = '登录已失效，请重新登录。'}) {
    _generation++;
    _client.token = null;
    _deletingToken = _forgetToken();
    _user = null;
    _permissions = const [];
    _error = message;
    notifyListeners();
  }

  Future<void> changePassword(String currentPassword, String newPassword) async {
    final gen = _generation;
    await _api.changePassword(currentPassword, newPassword);
    if (_disposed || gen != _generation) return;
    _expireSession(message: '密码已修改，请使用新密码登录。');
  }

  bool _disposed = false;

  @override
  void dispose() {
    _disposed = true;
    _client.onUnauthorized = null;
    _client.close();
    super.dispose();
  }
}
