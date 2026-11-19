import 'dart:collection';

import 'package:flutter/material.dart';

import '../../core/api_client.dart';
import '../../core/date_format.dart';
import '../../data/api.dart';
import '../../data/models.dart';
import '../../state/app_scope.dart';
import '../../widgets/async_view.dart';
import '../devices/device_detail_page.dart';

const _cloudLabels = {
  'NOT_ATTEMPTED': '未提交',
  'IN_FLIGHT': '提交中，仍待确认',
  'UNKNOWN': '结果未知',
  'SUCCEEDED': '云端已接受',
  'FAILED': '云端未接受',
};

String placePath(Place place, List<Place> places) {
  final names = [place.name];
  final seen = {place.id};
  var parentId = place.parentId;
  while (parentId != null && seen.add(parentId)) {
    final parent = places.where((candidate) => candidate.id == parentId).firstOrNull;
    if (parent == null) break;
    names.add(parent.name);
    parentId = parent.parentId;
  }
  return names.reversed.join(' / ');
}

class RegistrationListPage extends StatefulWidget {
  const RegistrationListPage({super.key, required this.scope});

  final RegistrationScope scope;

  @override
  State<RegistrationListPage> createState() => _RegistrationListPageState();
}

class _RegistrationListPageState extends State<RegistrationListPage> {
  late Future<Paginated<RegistrationSummary>> _data;
  int _page = 1;

  @override
  void initState() {
    super.initState();
    _data = _load(1);
  }

  Future<Paginated<RegistrationSummary>> _load(int page) =>
      AppScope.read(context).api.registrations(page: page, size: 20);

  void _goto(int page) => setState(() {
        _page = page;
        _data = _load(page);
      });

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('已有海康登记')),
    body: AsyncSection<Paginated<RegistrationSummary>>(
      future: _data,
      onRetry: () async => _goto(_page),
      builder: (page) {
        final rows = page.items.where((row) => row.driverKey == hikvisionDriverKey && row.mode == 'NEW_VENDOR');
        return ListView(padding: const EdgeInsets.all(16), children: [
          if (rows.isEmpty) const Text('本页没有海康新增登记'),
          for (final row in rows) Card(child: ListTile(
            title: Text(row.deviceName ?? row.externalSerial),
            subtitle: Text('${row.externalSerial} · ${_cloudLabels[row.cloudState] ?? row.cloudState ?? '未知'}${row.status == 'COMMISSIONED' ? ' · 本地已验收' : ''}'),
            trailing: const Icon(Icons.chevron_right),
            onTap: () async {
              await Navigator.of(context).push(MaterialPageRoute<void>(
                builder: (_) => RegistrationStatusPage(id: row.id, scope: widget.scope),
              ));
              if (mounted) _goto(_page);
            },
          )),
          Wrap(spacing: 12, children: [
            if (page.page > 1) OutlinedButton(onPressed: () => _goto(page.page - 1), child: const Text('上一页')),
            if (page.hasMore) OutlinedButton(onPressed: () => _goto(page.page + 1), child: const Text('下一页')),
          ]),
        ]);
      },
    ),
  );
}

class RegistrationStatusPage extends StatefulWidget {
  const RegistrationStatusPage({super.key, required this.id, required this.scope});

  final String id;
  final RegistrationScope scope;

  @override
  State<RegistrationStatusPage> createState() => _RegistrationStatusPageState();
}

class _RegistrationStatusPageState extends State<RegistrationStatusPage> {
  late Future<RegistrationDetail> _detail;
  final _note = TextEditingController();
  bool _busy = false;
  bool _cloudUnconfirmed = false;
  bool _commissionUnconfirmed = false;
  String? _commissionKey;
  String? _message;

  @override
  void initState() {
    super.initState();
    _detail = _load();
  }

  Future<RegistrationDetail> _load() => AppScope.read(context).api.registrationDetail(widget.id);

  void _refresh() => setState(() => _detail = _load());

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  Future<void> _run(Future<RegistrationDetail> Function() operation, {bool cloudWrite = false, bool commission = false}) async {
    if (_busy || !AppScope.read(context).canRegisterHikvision) return;
    setState(() { _busy = true; _message = null; });
    try {
      final result = await operation();
      if (!mounted) return;
      setState(() {
        _detail = Future.value(result);
        if (commission) _commissionUnconfirmed = false;
      });
    } on ApiError catch (error) {
      if (!mounted) return;
      setState(() {
        _message = error.toString();
        if (cloudWrite && error.isUnknown) _cloudUnconfirmed = true;
        if (commission) {
          _commissionUnconfirmed = error.isUnknown;
          if (!_commissionUnconfirmed) _commissionKey = null;
        }
        // GET only: do not replay a pending cloud submission.
        _detail = _load();
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<bool> _confirm(String title, String detail, String action) async =>
      await showDialog<bool>(context: context, builder: (context) => AlertDialog(
        title: Text(title), content: Text(detail), actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: Text(action)),
        ],
      )) ?? false;

  Future<void> _submitCloud(RegistrationSummary row) async {
    if (!await _confirm('确认向海康云提交', '序列号：${row.externalSerial}\n将执行一次厂商新增；云端接受后仍需本地验收。', '确认提交一次') || !mounted) return;
    final api = AppScope.read(context).api;
    await _run(() => api.resumeRegistration(widget.id), cloudWrite: true);
  }

  Future<void> _commission(RegistrationSummary row, Place place) async {
    final note = _note.text.trim();
    if (note.isEmpty || note.length > 1000) {
      setState(() => _message = '请填写不超过 1000 字的验收说明');
      return;
    }
    final scope = widget.scope;
    if (!_commissionUnconfirmed && (!await _confirm('确认本地验收', '资产归属：${scope.name} / ${placePath(place, scope.places)}\n序列号：${row.externalSerial}\n此操作仅建立本地资产及来源绑定。', '确认验收') || !mounted)) return;
    final api = AppScope.read(context).api;
    await _run(() => api.commissionRegistration(id: widget.id, tenantId: scope.id,
        placeId: place.id, acceptanceNote: note, key: _commissionKey ??= newIdempotencyKey()), commission: true);
  }

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: !_busy,
    child: Scaffold(
      appBar: AppBar(title: const Text('海康登记详情'), actions: [
        IconButton(onPressed: _busy ? null : _refresh, tooltip: '刷新登记', icon: const Icon(Icons.refresh)),
      ]),
      body: AsyncSection<RegistrationDetail>(future: _detail, onRetry: () async => _refresh(), builder: _body),
    ),
  );

  Widget _body(RegistrationDetail detail) {
    final row = detail.registration;
    final scope = widget.scope;
    final place = scope.places.where((place) => place.id == row.placeId).firstOrNull;
    final lastLookup = detail.events.where((event) => event.step == 'CLOUD_LOOKUP').lastOrNull;
    final complete = row.status == 'COMMISSIONED';
    final canWrite = AppScope.of(context).canRegisterHikvision && !complete && row.status != 'ABANDONED';
    final canSubmit = canWrite && ((!_cloudUnconfirmed &&
        (row.cloudState == 'NOT_ATTEMPTED' || row.cloudState == 'FAILED')) ||
        (row.cloudState == 'UNKNOWN' && lastLookup?.outcome == 'NOT_FOUND'));
    final canCheck = canWrite && const {'IN_FLIGHT', 'UNKNOWN'}.contains(row.cloudState);
    return ListView(padding: const EdgeInsets.all(16), children: [
      Text(row.deviceName ?? '海康相机', style: Theme.of(context).textTheme.headlineSmall),
      Text('机身序列号：${row.externalSerial}'),
      Text('设备型号：${detail.deviceModel ?? '未提供'}'),
      Text('本地归属：${scope.name} / ${place == null ? '位置未确认' : placePath(place, scope.places)}'),
      const SizedBox(height: 12),
      Text('云端状态：${_cloudLabels[row.cloudState] ?? row.cloudState ?? '未知'}'),
      Text('本地验收：${complete ? '已完成' : '尚未完成'}'),
      if (row.status == 'ABANDONED') const Text('登记已放弃'),
      if (detail.acceptanceNote case final note?) Text('验收说明：$note'),
      if (_message case final message?) Text(message),
      if (_busy) const LinearProgressIndicator(),
      if (_cloudUnconfirmed && !complete && row.cloudState != 'SUCCEEDED')
        const Text('提交未收到确认。先刷新登记或核对结果；不要重新新增。'),
      if (lastLookup?.outcome == 'PLATFORM_RESOLUTION_REQUIRED' && row.cloudState == 'UNKNOWN')
        const Text('厂商记录存在，但登记归属尚未确认。请由平台管理员处理，不要重复提交。'),
      if (canSubmit) FilledButton(onPressed: _busy ? null : () => _submitCloud(row), child: const Text('提交海康云')),
      if (canCheck) OutlinedButton(onPressed: _busy ? null : () {
        final api = AppScope.read(context).api;
        _run(() => api.reconcileRegistration(widget.id));
      }, child: const Text('按序列号只读核对')),
      if (canWrite && row.cloudState == 'SUCCEEDED') ...[
        if (place == null) const Text('原登记位置不可选，请联系平台确认本地归属。') else ...[
          const SizedBox(height: 12),
          TextField(controller: _note, readOnly: _busy || _commissionUnconfirmed, maxLength: 1000,
              decoration: const InputDecoration(labelText: '本地验收说明')),
          if (_commissionUnconfirmed) const Text('验收未确认。可使用相同说明确认原请求。'),
          FilledButton(onPressed: _busy ? null : () => _commission(row, place),
              child: Text(_commissionUnconfirmed ? '按原请求确认验收' : '本地验收')),
        ],
      ],
      if (complete && row.deviceId != null && AppScope.of(context).hasPermission('device:read'))
        OutlinedButton(onPressed: () => Navigator.of(context).push(MaterialPageRoute<void>(
          builder: (_) => DeviceDetailPage(deviceId: row.deviceId!),
        )), child: const Text('查看本地设备')),
      const SizedBox(height: 12),
      const Text('登记记录'),
      for (final event in detail.events.reversed) ListTile(
        dense: true, title: Text('${event.step} · ${event.outcome}'),
        subtitle: Text(orUnknown(formatDateTime(event.occurredAt))),
      ),
    ]);
  }
}
