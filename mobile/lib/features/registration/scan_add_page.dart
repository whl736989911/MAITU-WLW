import 'package:flutter/material.dart';

import '../../core/api_client.dart';
import '../../data/api.dart';
import '../../state/app_scope.dart';
import '../../widgets/async_view.dart';
import 'qr_camera_page.dart';
import 'registration_status_page.dart';

class ScanAddPage extends StatefulWidget {
  const ScanAddPage({super.key});

  @override
  State<ScanAddPage> createState() => _ScanAddPageState();
}

class _ScanAddPageState extends State<ScanAddPage> {
  late Future<(RegistrationScope, bool)> _setup;

  @override
  void initState() {
    super.initState();
    _setup = _load();
  }

  Future<(RegistrationScope, bool)> _load() async {
    final api = AppScope.read(context).api;
    final (scope, drivers) = await (api.registrationScope(), api.registrationDrivers()).wait;
    return (scope, drivers.any((driver) => driver.key == hikvisionDriverKey && driver.enabled && driver.configured));
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('海康相机新增')),
    body: AsyncSection<(RegistrationScope, bool)>(
      future: _setup,
      onRetry: () async => setState(() => _setup = _load()),
      builder: (data) => !AppScope.of(context).canRegisterHikvision
          ? const Center(child: Text('当前账号没有厂商新增所需权限'))
          : _DraftForm(scope: data.$1, configured: data.$2),
    ),
  );
}

class _DraftForm extends StatefulWidget {
  const _DraftForm({required this.scope, required this.configured});

  final RegistrationScope scope;
  final bool configured;

  @override
  State<_DraftForm> createState() => _DraftFormState();
}

class _DraftFormState extends State<_DraftForm> {
  final _form = GlobalKey<FormState>();
  final _serial = TextEditingController();
  final _name = TextEditingController();
  final _model = TextEditingController();
  final _type = TextEditingController();
  final _communication = TextEditingController();
  final _unit = TextEditingController();
  final _code = TextEditingController();
  final _location = TextEditingController();
  String? _place;
  String? _key;
  String? _error;
  bool _busy = false;
  bool _unknown = false;

  @override
  void dispose() {
    for (final controller in [_serial, _name, _model, _type, _communication, _unit, _code, _location]) {
      controller.dispose();
    }
    super.dispose();
  }

  Future<void> _scan() async {
    final serial = await Navigator.of(context).push<String>(
      MaterialPageRoute(builder: (_) => const QrCameraPage()),
    );
    if (mounted && serial != null) _serial.text = serial;
  }

  Future<void> _save() async {
    final scope = widget.scope;
    final app = AppScope.read(context);
    final place = _place;
    if (_busy || !app.canRegisterHikvision || !widget.configured ||
        !_form.currentState!.validate() || place == null) return;
    setState(() { _busy = true; _error = null; });
    try {
      final created = await app.api.createRegistration(
        tenantId: scope.id,
        key: _key ??= newIdempotencyKey(),
        externalSerial: _serial.text.trim(),
        deviceName: _name.text.trim(),
        deviceModel: _model.text.trim(),
        deviceType: int.parse(_type.text.trim()),
        communicationType: int.parse(_communication.text.trim()),
        vendorUnitId: _unit.text.trim(),
        placeId: place,
        verificationCode: _code.text.trim(),
        location: _location.text.trim(),
      );
      if (!mounted) return;
      _code.clear();
      Navigator.of(context).pushReplacement(MaterialPageRoute<void>(
        builder: (_) => RegistrationStatusPage(id: created.registration.id, scope: scope),
      ));
    } on ApiError catch (error) {
      if (!mounted) return;
      setState(() {
        _error = error.toString();
        _unknown = error.isUnknown;
        if (!_unknown) _key = null;
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _openDrafts() => Navigator.of(context).push(MaterialPageRoute<void>(
        builder: (_) => RegistrationListPage(scope: widget.scope),
      ));

  String? _serialError(String? value) {
    final text = (value ?? '').trim();
    if (!RegExp(r'^[\x21-\x7e]{1,200}$').hasMatch(text) || text.contains('://') || text.startsWith('//')) {
      return '请填写机身序列号（1–200 位安全 ASCII），不要填写链接';
    }
    return null;
  }

  String? _positiveCode(String? value) {
    final text = (value ?? '').trim();
    final code = int.tryParse(text);
    if (!RegExp(r'^\d+$').hasMatch(text) || code == null || code < 1 || code > 2147483647) {
      return '请输入厂商提供的正整数代码';
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final scope = widget.scope;
    final editable = !_busy && !_unknown;
    return PopScope(
      canPop: !_busy,
      child: Form(key: _form, child: ListView(padding: const EdgeInsets.all(16), children: [
        OutlinedButton.icon(onPressed: _busy ? null : _openDrafts,
            icon: const Icon(Icons.list_alt), label: const Text('查看已有海康登记')),
        const SizedBox(height: 12),
        if (!widget.configured)
          const Text('海康云接入未启用或凭据不可用。请联系平台配置；当前不会创建登记。')
        else if (scope.places.isEmpty)
          const Text('当前没有可选择的本单位位置，不能创建登记。')
        else ...[
          Text('本地单位：${scope.name}', style: Theme.of(context).textTheme.titleMedium),
          const Text('草稿只保存到本平台。厂商单位和本地位置必须明确；保存不表示海康已接受。'),
          const SizedBox(height: 12),
          OutlinedButton.icon(onPressed: editable ? _scan : null,
              icon: const Icon(Icons.qr_code_scanner), label: const Text('打开系统相机扫码')),
          _field(_serial, '机身序列号', editable, 200, validator: _serialError),
          _field(_name, '厂商设备名称', editable, 200),
          _field(_model, '厂商设备型号', editable, 100),
          _field(_type, '海康设备类型码', editable, 10, validator: _positiveCode, numeric: true),
          _field(_communication, '海康通信类型码', editable, 10, validator: _positiveCode, numeric: true),
          _field(_unit, '海康云目标单位 unitID', editable, 64),
          _field(_code, '验证码（可选）', editable, 200, required: false, secret: true),
          _field(_location, '厂商位置描述（可选）', editable, 500, required: false),
          DropdownButtonFormField<String>(
            decoration: const InputDecoration(labelText: '本地安装位置'),
            isExpanded: true,
            items: [for (final place in scope.places) DropdownMenuItem(value: place.id,
                child: Text(placePath(place, scope.places), overflow: TextOverflow.ellipsis))],
            onChanged: editable ? (value) => setState(() => _place = value) : null,
            validator: (value) => value == null ? '请选择本单位位置' : null,
          ),
          const SizedBox(height: 12),
          if (_error case final error?) Text(error),
          if (_unknown) const Text('草稿保存未确认。可先查看已有登记，或只按同一内容重试。'),
          FilledButton(onPressed: _busy ? null : _save,
              child: Text(_busy ? '保存中…' : _unknown ? '按原请求确认草稿' : '保存登记草稿')),
        ],
      ])),
    );
  }

  Widget _field(TextEditingController controller, String label, bool editable, int maxLength, {
    bool required = true, bool secret = false, bool numeric = false, String? Function(String?)? validator,
  }) => Padding(
    padding: const EdgeInsets.only(top: 8),
    child: TextFormField(
      controller: controller,
      readOnly: !editable,
      obscureText: secret,
      autocorrect: false,
      enableSuggestions: !secret,
      maxLength: maxLength,
      keyboardType: numeric ? TextInputType.number : TextInputType.text,
      decoration: InputDecoration(labelText: label, counterText: ''),
      validator: validator ?? (value) => required && (value ?? '').trim().isEmpty ? '请填写$label' : null,
    ),
  );
}
