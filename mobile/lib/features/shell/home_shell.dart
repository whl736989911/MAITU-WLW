import 'package:flutter/material.dart';

import '../alarms/alarm_list_page.dart';
import '../devices/device_list_page.dart';
import '../home/home_page.dart';
import '../mine/mine_page.dart';
import '../../state/app_scope.dart';

/// Customer-side bottom navigation: 首页 / 设备 / 报警 / 我的. The 设备 page is
/// also the entry point for the Hikvision camera scan-add flow (FAB).
class HomeShell extends StatefulWidget {
  const HomeShell({super.key});

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int _index = 0;

  static const _titles = <String>['首页', '设备', '报警', '我的'];

  @override
  Widget build(BuildContext context) {
    final state = AppScope.of(context);
    final pages = <Widget>[
      state.hasPermission('summary:read') ? const HomePage() : const _Unavailable(),
      state.hasPermission('device:read') || state.canRegisterHikvision
          ? DeviceListPage(canRead: state.hasPermission('device:read'), canRegister: state.canRegisterHikvision)
          : const _Unavailable(),
      state.hasPermission('alarm:read') ? const AlarmListPage() : const _Unavailable(),
      const MinePage(),
    ];
    return Scaffold(
      appBar: AppBar(title: Text(_titles[_index]), centerTitle: false),
      body: IndexedStack(index: _index, children: pages),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _index,
        onDestinationSelected: (i) => setState(() => _index = i),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.home_outlined), selectedIcon: Icon(Icons.home), label: '首页'),
          NavigationDestination(icon: Icon(Icons.devices_other_outlined), selectedIcon: Icon(Icons.devices_other), label: '设备'),
          NavigationDestination(icon: Icon(Icons.notifications_outlined), selectedIcon: Icon(Icons.notifications), label: '报警'),
          NavigationDestination(icon: Icon(Icons.person_outline), selectedIcon: Icon(Icons.person), label: '我的'),
        ],
      ),
    );
  }
}

class _Unavailable extends StatelessWidget {
  const _Unavailable();

  @override
  Widget build(BuildContext context) =>
      const Center(child: Text('当前账号未获此项读取权限'));
}
