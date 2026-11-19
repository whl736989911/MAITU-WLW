import 'package:flutter/material.dart';

import '../../core/api_client.dart';
import '../../core/date_format.dart';
import '../../data/api.dart';
import '../../data/models.dart';
import '../../state/app_scope.dart';
import '../../widgets/async_view.dart';
import '../../widgets/status.dart';

class AlarmDetailPage extends StatefulWidget {
  const AlarmDetailPage({super.key, required this.alarmId});

  final String alarmId;

  @override
  State<AlarmDetailPage> createState() => _AlarmDetailPageState();
}

class _AlarmDetailPageState extends State<AlarmDetailPage> {
  late Future<Alarm> _alarm;
  Future<Paginated<AlarmTraceEntry>>? _history;
  int _historyPage = 1;

  @override
  void initState() {
    super.initState();
    _alarm = AppScope.read(context).api.alarm(widget.alarmId);
  }

  void _refresh() {
    final api = AppScope.read(context).api;
    setState(() {
      _alarm = api.alarm(widget.alarmId);
      if (_history != null) _history = api.alarmHistory(widget.alarmId, size: 20);
      _historyPage = 1;
    });
  }

  void _loadHistory(int page) => setState(() {
        _historyPage = page;
        _history = AppScope.read(context).api.alarmHistory(widget.alarmId, page: page, size: 20);
      });

  Future<void> _review(Alarm snapshot) async {
    final saved = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      isDismissible: false,
      enableDrag: false,
      useSafeArea: true,
      builder: (_) => _ReviewForm(alarm: snapshot),
    );
    if (mounted && saved != null) _refresh();
  }

  @override
  Widget build(BuildContext context) {
    final state = AppScope.of(context);
    final history = _history;
    return Scaffold(
      appBar: AppBar(title: const Text('警情详情'), actions: [
        IconButton(onPressed: _refresh, tooltip: '刷新', icon: const Icon(Icons.refresh)),
      ]),
      body: AsyncSection<Alarm>(
        future: _alarm,
        onRetry: () async => _refresh(),
        builder: (alarm) => ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Text(alarm.deviceName ?? '未上报设备名称', style: Theme.of(context).textTheme.headlineSmall),
            if (alarm.address case final address?) Text(address),
            const SizedBox(height: 12),
            Text('设备物理状态：${statusLabel(alarm.physicalState, physicalStateLabels)}'),
            Text('人工处置状态：${statusLabel(alarm.responseState, responseStateLabels)}'),
            Text('来源发生时间：${orUnknown(formatDateTime(alarm.occurredAt))}${alarm.timeQuality == 'LEGACY_UNVERIFIED' ? '（历史时间未核实）' : ''}'),
            Text('平台记录时间：${orUnknown(formatDateTime(alarm.recordedAt))}'),
            if (alarm.conclusion case final conclusion?)
              Text('复核结论：${statusLabel(conclusion, conclusionLabels)}'),
            const SizedBox(height: 16),
            if (state.hasPermission('alarm:review'))
              FilledButton.icon(onPressed: () => _review(alarm), icon: const Icon(Icons.fact_check_outlined), label: const Text('人工复核')),
            if (state.hasPermission('alarm:history')) ...[
              OutlinedButton(onPressed: () => _loadHistory(1), child: const Text('查看业务轨迹')),
              if (history != null) AsyncSection<Paginated<AlarmTraceEntry>>(
                future: history,
                onRetry: () async => _loadHistory(_historyPage),
                builder: (page) => Column(children: [
                  if (page.items.isEmpty) const Text('暂无轨迹'),
                  for (final event in page.items) ListTile(
                    title: Text(event.action == 'REVIEWED' ? '人工复核' : event.action),
                    subtitle: Text([
                      event.actorLabel.isEmpty ? '处理人未知' : event.actorLabel,
                      orUnknown(formatDateTime(event.recordedAt)),
                      if (event.remark case final remark?) remark,
                    ].join('\n')),
                  ),
                  if (page.page > 1 || page.hasMore) Wrap(spacing: 12, children: [
                    if (page.page > 1) TextButton(onPressed: () => _loadHistory(page.page - 1), child: const Text('上一页')),
                    if (page.hasMore) TextButton(onPressed: () => _loadHistory(page.page + 1), child: const Text('下一页')),
                  ]),
                ]),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _ReviewForm extends StatefulWidget {
  const _ReviewForm({required this.alarm});

  final Alarm alarm;

  @override
  State<_ReviewForm> createState() => _ReviewFormState();
}

class _ReviewFormState extends State<_ReviewForm> {
  final _form = GlobalKey<FormState>();
  final _remark = TextEditingController();
  String? _conclusion;
  String? _key;
  String? _error;
  bool _busy = false;
  bool _unknown = false;
  bool _conflict = false;

  @override
  void dispose() {
    _remark.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final state = AppScope.read(context);
    final conclusion = _conclusion;
    if (_busy || _conflict || !state.hasPermission('alarm:review') ||
        conclusion == null || !_form.currentState!.validate()) return;
    setState(() { _busy = true; _error = null; });
    try {
      await state.api.reviewAlarm(
        id: widget.alarm.id,
        expectedRevision: widget.alarm.revision,
        conclusion: conclusion,
        remark: _remark.text.trim(),
        key: _key ??= newIdempotencyKey(),
      );
      if (mounted) Navigator.of(context).pop(true);
    } on ApiError catch (error) {
      if (!mounted) return;
      setState(() {
        _error = error.toString();
        _unknown = error.isUnknown;
        _conflict = error.isConflict;
        if (!_unknown) _key = null;
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final editable = !_busy && !_unknown && !_conflict;
    return PopScope(
      canPop: !_busy,
      child: Padding(
        padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
        child: Form(
          key: _form,
          child: ListView(padding: const EdgeInsets.all(20), shrinkWrap: true, children: [
            Text('人工复核', style: Theme.of(context).textTheme.titleLarge),
            const Text('按现场核查填写；处置不会更改设备物理状态。'),
            const SizedBox(height: 12),
            DropdownButtonFormField<String>(
              decoration: const InputDecoration(labelText: '现场核查结论'),
              items: [for (final code in conclusions) DropdownMenuItem(value: code, child: Text(conclusionLabels[code]!))],
              onChanged: editable ? (value) => setState(() => _conclusion = value) : null,
              validator: (value) => value == null ? '请选择结论' : null,
            ),
            const SizedBox(height: 8),
            TextFormField(controller: _remark, readOnly: !editable, minLines: 2, maxLines: 4, maxLength: 2000,
                decoration: const InputDecoration(labelText: '现场情况 / 处置备注'),
                validator: (value) => (value ?? '').trim().isEmpty ? '请填写处置备注' : null),
            if (_error case final error?) Text(error),
            if (_unknown) const Text('结果未确认。只可用同一内容和请求编号重试，或先读取当前警情。'),
            if (_conflict) const Text('记录已更新，请读取新版本后再复核。'),
            FilledButton(onPressed: _busy || _conflict ? null : _submit,
                child: Text(_busy ? '提交中…' : _unknown ? '按原请求确认结果' : '保存复核')),
            TextButton(onPressed: _busy ? null : () => Navigator.of(context).pop(_unknown || _conflict ? false : null),
                child: Text(_unknown || _conflict ? '刷新警情' : '返回')),
          ]),
        ),
      ),
    );
  }
}
