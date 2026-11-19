import 'package:flutter/material.dart';

import '../../core/date_format.dart';
import '../../data/models.dart';
import '../../state/app_scope.dart';
import '../../widgets/status.dart';
import 'alarm_detail_page.dart';

/// Customer alarm list with physical-state filter. Review (处置) happens on the
/// detail page through the single existing review endpoint; the list only
/// reads.
class AlarmListPage extends StatefulWidget {
  const AlarmListPage({super.key});

  @override
  State<AlarmListPage> createState() => _AlarmListPageState();
}

class _AlarmListPageState extends State<AlarmListPage> {
  String? _physicalState;
  final List<Alarm> _items = [];
  int _page = 1;
  int _total = 0;
  bool _loading = false;
  bool _initialLoaded = false;
  String? _error;
  int _loadGeneration = 0;

  @override
  void initState() {
    super.initState();
    _load(reset: true);
  }

  Future<void> _load({bool reset = false}) async {
    if (_loading && !reset) return;
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
      final result = await AppScope.read(context).api.alarms(
        page: _page,
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

  bool get _hasMore => _items.length < _total;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        SizedBox(
          height: 44,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
            children: [
              _chip('全部', _physicalState == null, () => _select(null)),
              _chip('火警', _physicalState == 'FIRE', () => _select('FIRE')),
              _chip('正常', _physicalState == 'NORMAL', () => _select('NORMAL')),
            ],
          ),
        ),
        Expanded(child: _buildList()),
      ],
    );
  }

  Widget _chip(String label, bool selected, VoidCallback onTap) {
    return Padding(
      padding: const EdgeInsets.only(right: 8),
      child: ChoiceChip(label: Text(label), selected: selected, onSelected: (_) => onTap()),
    );
  }

  void _select(String? value) {
    if (_physicalState == value) return;
    setState(() => _physicalState = value);
    _load(reset: true);
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
      return const Center(child: Text('暂无报警', style: TextStyle(color: Colors.grey)));
    }
    return ListView.separated(
      padding: const EdgeInsets.all(16),
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
        return _AlarmCard(alarm: _items[index], onReturn: () => _load(reset: true));
      },
    );
  }
}

class _AlarmCard extends StatelessWidget {
  const _AlarmCard({required this.alarm, required this.onReturn});

  final Alarm alarm;
  final VoidCallback onReturn;

  @override
  Widget build(BuildContext context) {
    final physical = statusLabel(alarm.physicalState, physicalStateLabels);
    final response = statusLabel(alarm.responseState, responseStateLabels);
    final isFire = alarm.isFire;
    final isUnhandled = alarm.isUnhandled;
    return Card(
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: () async {
          await Navigator.of(context).push(
            MaterialPageRoute<void>(builder: (_) => AlarmDetailPage(alarmId: alarm.id)),
          );
          if (context.mounted) onReturn();
        },
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(
                    isFire ? Icons.local_fire_department : Icons.check_circle_outline,
                    color: isFire ? const Color(0xFFD32F2F) : const Color(0xFF2E7D32),
                    size: 20,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      alarm.deviceName ?? '未上报',
                      style: const TextStyle(fontWeight: FontWeight.w600),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  StatusBadge(label: response, code: alarm.responseState),
                ],
              ),
              const SizedBox(height: 8),
              if (alarm.address != null && alarm.address!.isNotEmpty)
                Text('地址 ${alarm.address}', style: const TextStyle(fontSize: 12, color: Colors.grey)),
              Text(
                '来源发生时间 ${orUnknown(formatDateTime(alarm.occurredAt))}${alarm.timeQuality == 'LEGACY_UNVERIFIED' ? '（历史时间未核实）' : ''}',
                style: const TextStyle(fontSize: 12, color: Colors.grey),
              ),
              const SizedBox(height: 6),
              Row(
                children: [
                  StatusBadge(label: physical, code: alarm.physicalState),
                  if (alarm.conclusion != null && alarm.conclusion!.isNotEmpty) ...[
                    const SizedBox(width: 8),
                    StatusBadge(label: statusLabel(alarm.conclusion, conclusionLabels), code: alarm.conclusion),
                  ],
                  if (isUnhandled) ...[
                    const SizedBox(width: 8),
                    const Text('待处置', style: TextStyle(fontSize: 12, color: Color(0xFFD32F2F))),
                  ],
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
