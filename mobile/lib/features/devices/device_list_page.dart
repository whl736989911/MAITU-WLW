import 'package:flutter/material.dart';

import '../../core/date_format.dart';
import '../../data/models.dart';
import '../../state/app_scope.dart';
import '../../widgets/status.dart';
import '../registration/scan_add_page.dart';
import 'device_detail_page.dart';

/// Customer device list: a single unified list across the two real device
/// sources (legacy smoke and Hikvision cloud). Filtering maps to the backend's
/// own query parameters; status comes from the server's `status_display`.
class DeviceListPage extends StatefulWidget {
  const DeviceListPage({super.key, required this.canRead, required this.canRegister});

  final bool canRegister;
  final bool canRead;

  @override
  State<DeviceListPage> createState() => _DeviceListPageState();
}

class _DeviceListPageState extends State<DeviceListPage> {
  final _search = TextEditingController();
  String _keyword = '';
  String? _connectivity;
  String? _physicalState;

  final List<Device> _items = [];
  int _page = 1;
  int _total = 0;
  bool _loading = false;
  bool _initialLoaded = false;
  String? _error;
  int _loadGeneration = 0;

  @override
  void initState() {
    super.initState();
    if (widget.canRead) _load(reset: true);
  }

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  Future<void> _load({bool reset = false}) async {
    if (!widget.canRead || (_loading && !reset)) return;
    final generation = ++_loadGeneration;
    if (reset) {
      _page = 1;
      _total = 0;
      _items.clear();
      _initialLoaded = false;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final result = await AppScope.read(context).api.devices(
        page: _page,
        keyword: _keyword,
        connectivity: _connectivity,
        physicalState: _physicalState,
      );
      if (!mounted || generation != _loadGeneration) return;
      setState(() {
        _items.addAll(result.items);
        _total = result.total;
        _page += 1;
        _initialLoaded = true;
      });
    } catch (e) {
      if (!mounted || generation != _loadGeneration) return;
      setState(() => _error = e.toString());
    } finally {
      if (mounted && generation == _loadGeneration) setState(() => _loading = false);
    }
  }

  void _applyFilters() {
    setState(() {
      _keyword = _search.text.trim();
    });
    _load(reset: true);
  }

  bool get _hasMore => _items.length < _total;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      floatingActionButton: widget.canRegister
          ? FloatingActionButton.extended(
              heroTag: 'scan-add',
              onPressed: () async {
                await Navigator.of(context).push(
                  MaterialPageRoute<void>(builder: (_) => const ScanAddPage()),
                );
                if (mounted) await _load(reset: true);
              },
              icon: const Icon(Icons.qr_code_scanner),
              label: const Text('扫码新增'),
            )
          : null,
      body: !widget.canRead ? const Center(child: Text('当前账号未获设备读取权限')) : Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
            child: TextField(
              controller: _search,
              decoration: InputDecoration(
                hintText: '搜索名称 / 设备标识',
                prefixIcon: const Icon(Icons.search),
                suffixIcon: IconButton(
                  icon: const Icon(Icons.clear),
                  onPressed: () {
                    _search.clear();
                    _applyFilters();
                  },
                ),
              ),
              textInputAction: TextInputAction.search,
              onSubmitted: (_) => _applyFilters(),
            ),
          ),
          _FilterChips(
            connectivity: _connectivity,
            physicalState: _physicalState,
            onChanged: (c, p) {
              setState(() {
                _connectivity = c;
                _physicalState = p;
              });
              _load(reset: true);
            },
          ),
          Expanded(child: _buildList()),
        ],
      ),
    );
  }

  Widget _buildList() {
    if (_error != null && _items.isEmpty) {
      return ListView(children: [
        Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            children: [
              Text(_error!, textAlign: TextAlign.center),
              const SizedBox(height: 12),
              OutlinedButton(onPressed: () => _load(reset: true), child: const Text('重试')),
            ],
          ),
        ),
      ]);
    }
    if (_initialLoaded && _items.isEmpty) {
      return const Center(child: Text('暂无设备', style: TextStyle(color: Colors.grey)));
    }
    return ListView.separated(
      padding: EdgeInsets.fromLTRB(16, 16, 16, widget.canRegister ? 88 : 16),
      itemCount: _items.length + (_hasMore || _loading ? 1 : 0),
      separatorBuilder: (_, __) => const SizedBox(height: 8),
      itemBuilder: (context, index) {
        if (index >= _items.length) {
          return Padding(
            padding: const EdgeInsets.symmetric(vertical: 16),
            child: Center(
              child: _loading
                  ? const SizedBox(height: 24, width: 24, child: CircularProgressIndicator(strokeWidth: 2))
                  : Column(children: [
                      if (_error case final error?) Text(error),
                      OutlinedButton(onPressed: () => _load(), child: Text(_error == null ? '加载更多' : '重试')),
                    ]),
            ),
          );
        }
        return _DeviceCard(device: _items[index]);
      },
    );
  }
}

class _FilterChips extends StatelessWidget {
  const _FilterChips({required this.connectivity, required this.physicalState, required this.onChanged});

  final String? connectivity;
  final String? physicalState;
  final void Function(String? connectivity, String? physicalState) onChanged;

  @override
  Widget build(BuildContext context) {
    Widget chip(String label, bool selected, VoidCallback onTap) {
      return Padding(
        padding: const EdgeInsets.only(right: 8),
        child: ChoiceChip(
          label: Text(label),
          selected: selected,
          onSelected: (_) => onTap(),
        ),
      );
    }

    return SizedBox(
      height: 44,
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
        children: [
          chip('全部', connectivity == null && physicalState == null, () => onChanged(null, null)),
          chip('在线', connectivity == 'ONLINE', () => onChanged('ONLINE', null)),
          chip('离线', connectivity == 'OFFLINE', () => onChanged('OFFLINE', null)),
          chip('未激活', connectivity == 'UNACTIVATED', () => onChanged('UNACTIVATED', null)),
          chip('状态未确认', connectivity == 'UNKNOWN', () => onChanged('UNKNOWN', null)),
          chip('火警', physicalState == 'FIRE', () => onChanged(null, 'FIRE')),
        ],
      ),
    );
  }
}

class _DeviceCard extends StatelessWidget {
  const _DeviceCard({required this.device});

  final Device device;

  @override
  Widget build(BuildContext context) {
    final status = device.statusDisplay;
    final customerStatus = status?.customerStatus;
    final label = statusLabel(customerStatus, customerStatusLabels);
    final battery = device.batteryPercent;
    final name = device.name ?? device.deviceEui ?? '未命名设备';
    return Card(
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: () => Navigator.of(context).push(
          MaterialPageRoute<void>(builder: (_) => DeviceDetailPage(deviceId: device.id)),
        ),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            name,
                            style: const TextStyle(fontWeight: FontWeight.w600),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                        StatusBadge(label: label, code: customerStatus),
                      ],
                    ),
                    const SizedBox(height: 6),
                    if (device.deviceEui != null && device.deviceEui!.isNotEmpty)
                      Text('标识 ${device.deviceEui}', style: const TextStyle(fontSize: 12, color: Colors.grey)),
                    if (device.address != null && device.address!.isNotEmpty)
                      Text('地址 ${device.address}', style: const TextStyle(fontSize: 12, color: Colors.grey)),
                    if (device.lastMessageAt != null)
                      Text(
                        '最近上报 ${orUnknown(formatDateTime(device.lastMessageAt))}',
                        style: const TextStyle(fontSize: 12, color: Colors.grey),
                      ),
                  ],
                ),
              ),
              if (battery != null)
                Padding(
                  padding: const EdgeInsets.only(left: 12),
                  child: Column(
                    children: [
                      Icon(
                        battery <= 20 ? Icons.battery_alert : Icons.battery_full,
                        color: battery <= 20 ? const Color(0xFFF57C00) : const Color(0xFF2E7D32),
                      ),
                      Text('$battery%', style: const TextStyle(fontSize: 12)),
                    ],
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
