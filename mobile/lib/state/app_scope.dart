import 'package:flutter/widgets.dart';

import 'app_state.dart';

/// Exposes [AppState] to the widget tree, mirroring the existing app's
/// `InheritedNotifier` pattern.
class AppScope extends InheritedNotifier<AppState> {
  const AppScope({super.key, required AppState state, required super.child})
      : super(notifier: state);

  static AppState of(BuildContext context) {
    final scope = context.dependOnInheritedWidgetOfExactType<AppScope>();
    assert(scope != null, 'AppScope missing from widget tree');
    return scope!.notifier!;
  }

  static AppState read(BuildContext context) =>
      context.getInheritedWidgetOfExactType<AppScope>()!.notifier!;
}
