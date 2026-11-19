import 'package:flutter/material.dart';

import '../../core/date_format.dart';
import '../../data/api.dart';
import '../../data/models.dart';
import '../../state/app_scope.dart';
import '../../widgets/async_view.dart';
import '../../widgets/status.dart';

/// Device detail. Online status, business status and source metadata all come
/// from the backend; the page renders the server's own `status_display` and
/// never re-derives a status from message timestamps.
class DeviceDetailPage extends StatefulWidget {
  const DeviceDetailPage({super.key, required this.deviceId});

  final String deviceId;

  @override
  State<DeviceDetailPage> createState() => _DeviceDetailPageState();
}

class _DeviceDetailPageState extends State<DeviceDetailPage> {
  late Future<(DeviceDetail, List<String>?)> _detail;

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _load() => _detail = _loadDevice();

  Future<(DeviceDetail, List<String>?)> _loadDevice() async {
    final state = AppScope.read(context);
    final detail = await state.api.device(widget.deviceId);
    List<String>? sources;
    if (state.hasPermission('device-services:read')) {
      try {
        sources = await state.api.activeSourceDrivers(widget.deviceId);
      } on Exception {
        // An unavailable source label does not hide the scoped asset record.
      }
    }
    return (detail, sources);
  }

  void _refresh() => setState(_load);

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('设备详情'), actions: [
        IconButton(onPressed: _refresh, tooltip: '刷新', icon: const Icon(Icons.refresh)),
      ]),
      body: AsyncSection<(DeviceDetail, List<String>?)>(
        future: _detail,
        onRetry: () async => _refresh(),
        builder: (result) => _DetailView(device: result.$1.device, asOf: result.$1.asOf,
            timezone: result.$1.timezone, sources: result.$2,
            canReadSources: AppScope.of(context).hasPermission('device-services:read')),
      ),
    );
  }
}

class _DetailView extends StatelessWidget {
  const _DetailView({required this.device, this.asOf, this.timezone,
      required this.sources, required this.canReadSources});

  final Device device;
  final String? asOf;
  final String? timezone;
  final List<String>? sources;
  final bool canReadSources;

  @override
  Widget build(BuildContext context) {
    final status = device.statusDisplay;
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        device.name ?? device.deviceEui ?? '未命名设备',
                        style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
                      ),
                    ),
                    StatusBadge(
                      label: statusLabel(status?.customerStatus, customerStatusLabels),
                      code: status?.customerStatus,
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                _row('在线状态', statusLabel(status?.onlineStatus, onlineStatusLabels)),
                if (status != null && status.businessStatuses.isNotEmpty)
                  _row(
                    '业务状态',
                    status.businessStatuses.map((b) => b.label).join(' / '),
                  ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 12),
        Card(
          child: Column(
            children: [
              _row('设备标识', device.deviceEui ?? '未上报'),
              _row('型号', device.model ?? '未上报'),
              _row('地址', device.address ?? '未上报'),
              _row('所属位置', device.placeName ?? '未上报'),
              _row('产品', device.productName ?? '未上报'),
              if (canReadSources)
                _row('有效接入来源', switch (sources) {
                  null => '读取失败，请刷新',
                  [] => '无有效绑定',
                  final values => values.map(_sourceLabel).join(' / '),
                }),
              _row('系统类型', device.systemType ?? '未上报'),
              _row('设备分类', device.deviceCategory ?? '未上报'),
              _row('接入状态', device.integrationState ?? '未上报'),
              _row('电量', device.batteryPercent == null ? '未上报' : '${device.batteryPercent}%'),
              _row('最近上报', orUnknown(formatDateTime(device.lastMessageAt))),
            ],
          ),
        ),
        if (asOf != null)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: Text(
              '数据时间 ${orUnknown(formatDateTime(asOf))}${timezone != null ? ' · $timezone' : ''}',
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 12, color: Colors.grey),
            ),
          ),
      ],
    );
  }

  Widget _row(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(width: 96, child: Text(label, style: const TextStyle(color: Colors.grey))),
          Expanded(child: Text(value)),
        ],
      ),
    );
  }
}

String _sourceLabel(String driver) => switch (driver) {
      hikvisionDriverKey => '海康云',
      'LEGACY_REALTIME' => '旧来源实时接入',
      _ => driver,
    };
