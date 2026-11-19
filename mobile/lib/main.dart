import 'package:flutter/material.dart';

import 'core/config.dart';
import 'features/auth/change_password_page.dart';
import 'features/auth/login_page.dart';
import 'features/shell/home_shell.dart';
import 'state/app_scope.dart';
import 'state/app_state.dart';

void main() {
  runApp(const FireApp());
}

class FireApp extends StatefulWidget {
  const FireApp({super.key});

  @override
  State<FireApp> createState() => _FireAppState();
}

class _FireAppState extends State<FireApp> {
  final AppState _state = AppState();

  @override
  void initState() {
    super.initState();
    // Revalidate a persisted token before deciding what to show; the app never
    // fabricates a signed-in state from a stale token.
    if (AppConfig.isConfigured) _state.restore();
  }

  @override
  void dispose() {
    _state.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scheme = ColorScheme.fromSeed(seedColor: const Color(0xFF0B63CE));
    return AppScope(
      state: _state,
      child: Builder(builder: (context) => MaterialApp(
        key: ValueKey(AppScope.of(context).generation),
        title: AppConfig.appName,
        debugShowCheckedModeBanner: false,
        theme: ThemeData(
          colorScheme: scheme,
          useMaterial3: true,
          scaffoldBackgroundColor: const Color(0xFFF4F6F8),
          cardTheme: CardThemeData(
            elevation: 0,
            margin: EdgeInsets.zero,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          ),
          inputDecorationTheme: const InputDecorationTheme(
            filled: true,
            fillColor: Colors.white,
            border: OutlineInputBorder(
              borderRadius: BorderRadius.all(Radius.circular(10)),
              borderSide: BorderSide.none,
            ),
            contentPadding: EdgeInsets.symmetric(horizontal: 14, vertical: 14),
          ),
        ),
        home: _Root(),
      )),
    );
  }
}

class _Root extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    final state = AppScope.of(context);

    if (!AppConfig.isConfigured) {
      return const _NotConfigured();
    }

    if (state.restoring) {
      return const Scaffold(
        body: Center(child: CircularProgressIndicator()),
      );
    }

    if (!state.signedIn) {
      return const LoginPage();
    }

    if (state.mustChangePassword) {
      return const ChangePasswordPage();
    }

    return const HomeShell();
  }
}

class _NotConfigured extends StatelessWidget {
  const _NotConfigured();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.link_off, size: 48, color: Colors.grey),
              const SizedBox(height: 16),
              const Text('未配置平台地址', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600)),
              const SizedBox(height: 8),
              const Text(
                '构建时通过 --dart-define=API_BASE_URL=https://<host> 指定平台 API 地址。'
                '仅调试版允许回环 HTTP；无地址时不会回退到演示数据。',
                textAlign: TextAlign.center,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
