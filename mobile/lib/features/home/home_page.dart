import 'package:flutter/material.dart';

import '../../data/models.dart';
import '../../state/app_scope.dart';
import '../../widgets/async_view.dart';

/// Customer home with dashboard totals read from the platform.
class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  late Future<Summary> _future;

  @override
  void initState() {
    super.initState();
    _future = _load();
  }

  Future<Summary> _load() => AppScope.read(context).api.summary();

  void _refresh() {
    setState(() {
      _future = _load();
    });
  }

  @override
  Widget build(BuildContext context) {
    final state = AppScope.of(context);
    return RefreshIndicator(
      onRefresh: () async => _refresh(),
      child: ListView(
        padding: const EdgeInsets.all(16),
        physics: const AlwaysScrollableScrollPhysics(),
        children: [
          Text(
            _greeting(state.user?.name),
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 16),
          AsyncSection<Summary>(
            future: _future,
            onRetry: () async => _refresh(),
            builder: (summary) => _SummaryGrid(summary: summary),
          ),
        ],
      ),
    );
  }

  String _greeting(String? name) =>
      name == null || name.isEmpty ? '欢迎使用消防物联平台' : '你好，$name';
}

class _SummaryGrid extends StatelessWidget {
  const _SummaryGrid({required this.summary});

  final Summary summary;

  @override
  Widget build(BuildContext context) {
    final items = <(String, int?, IconData, Color)>[
      ('设备总数', summary.deviceTotal, Icons.devices_other, const Color(0xFF0B63CE)),
      ('在线', summary.online, Icons.check_circle_outline, const Color(0xFF2E7D32)),
      ('离线', summary.offline, Icons.cloud_off, const Color(0xFF757575)),
      ('未激活', summary.unactivated, Icons.help_outline, const Color(0xFF757575)),
      ('今日报警', summary.todayAlarms, Icons.warning_amber, const Color(0xFFD32F2F)),
      ('待复核', summary.unhandledAlarms, Icons.notifications_active, const Color(0xFFD32F2F)),
      ('低电量', summary.lowBattery, Icons.battery_alert, const Color(0xFFF57C00)),
    ];
    return GridView.count(
      crossAxisCount: 2,
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      mainAxisSpacing: 12,
      crossAxisSpacing: 12,
      childAspectRatio: 1.7,
      children: items.map((item) {
        final (label, value, icon, color) = item;
        return Card(
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Row(
              children: [
                Icon(icon, color: color),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        value == null ? '--' : '$value',
                        style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w700),
                      ),
                      Text(label, style: const TextStyle(color: Colors.grey, fontSize: 13)),
                    ],
                  ),
                ),
              ],
            ),
          ),
        );
      }).toList(),
    );
  }
}
