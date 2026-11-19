import 'dart:convert';

import 'package:fire_platform_mobile/features/devices/device_list_page.dart';
import 'package:fire_platform_mobile/state/app_scope.dart';
import 'package:fire_platform_mobile/state/app_state.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

Map<String, dynamic> device(String id, String name, String connectivity, String status) => {
  'id': id,
  'name': name,
  'connectivity': connectivity,
  'status_display': {'online_status': connectivity, 'customer_status': status},
};

void main() {
  testWidgets('unconfirmed connectivity is filterable without hiding the server fire status', (tester) async {
    final unconfirmed = device('uncertain', 'Unconfirmed smoke detector', 'UNKNOWN', 'UNKNOWN');
    final fire = device('fire', 'Camera reporting fire', 'UNKNOWN', 'FIRE');
    final offline = device('offline', 'Confirmed offline detector', 'OFFLINE', 'OFFLINE');
    final state = http.runWithClient(() => AppState(baseUrl: 'https://fixture.invalid'), () => MockClient((request) async {
      expect(request.url.path, '/api/customer/devices');
      final items = switch (request.url.queryParameters['connectivity']) {
        'UNKNOWN' => [unconfirmed, fire],
        'OFFLINE' => [offline],
        null => [unconfirmed, fire, offline],
        _ => throw StateError('Unexpected connectivity filter'),
      };
      return http.Response(jsonEncode({'items': items, 'total': items.length}), 200);
    }));
    addTearDown(state.dispose);
    await tester.pumpWidget(AppScope(
      state: state,
      child: const MaterialApp(home: DeviceListPage(canRead: true, canRegister: false)),
    ));
    await tester.pumpAndSettle();

    final unconfirmedChip = find.widgetWithText(ChoiceChip, '状态未确认');
    expect(unconfirmedChip, findsOneWidget);
    await tester.tap(unconfirmedChip);
    await tester.pumpAndSettle();

    final unconfirmedCard = find.ancestor(of: find.text('Unconfirmed smoke detector'), matching: find.byType(Card));
    final fireCard = find.ancestor(of: find.text('Camera reporting fire'), matching: find.byType(Card));
    expect(find.descendant(of: unconfirmedCard, matching: find.text('状态未确认')), findsOneWidget);
    expect(find.descendant(of: fireCard, matching: find.text('报警')), findsOneWidget);
    expect(find.text('Confirmed offline detector'), findsNothing);

    final offlineChip = find.widgetWithText(ChoiceChip, '离线');
    await tester.tap(offlineChip);
    await tester.pumpAndSettle();
    expect(find.text('Confirmed offline detector'), findsOneWidget);
    expect(unconfirmedCard, findsNothing);
    expect(fireCard, findsNothing);
  });
}
