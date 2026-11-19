import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

import '../../core/qr_parser.dart';

class QrCameraPage extends StatefulWidget {
  const QrCameraPage({super.key});

  @override
  State<QrCameraPage> createState() => _QrCameraPageState();
}

class _QrCameraPageState extends State<QrCameraPage> {
  final _scanner = MobileScannerController(formats: const [BarcodeFormat.qrCode]);
  bool _finished = false;
  String? _hint;

  void _detect(BarcodeCapture capture) {
    if (_finished || !mounted || ModalRoute.of(context)?.isCurrent != true) return;
    final payloads = capture.barcodes.map((code) => code.rawValue).whereType<String>().toSet();
    if (payloads.length != 1) {
      if (payloads.isNotEmpty) setState(() => _hint = '请只对准一张设备机身标签');
      return;
    }
    try {
      final serial = parseQrSerial(payloads.single).serial;
      _finished = true;
      Navigator.of(context).pop(serial);
    } on QrParseFailure catch (error) {
      if (_hint != error.message) setState(() => _hint = error.message);
    }
  }

  @override
  void dispose() {
    _scanner.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('扫描机身二维码')),
    body: SafeArea(child: Column(children: [
      Expanded(child: MobileScanner(
        controller: _scanner,
        onDetect: _detect,
        errorBuilder: (context, error, child) => Center(child: Padding(
          padding: const EdgeInsets.all(20),
          child: Text(error.errorCode == MobileScannerErrorCode.permissionDenied
              ? '相机权限未授予。请在系统应用设置中开启相机权限，或返回手动填写。'
              : '无法启动相机，可返回手动填写机身序列号。',
              textAlign: TextAlign.center),
        )),
      )),
      Padding(padding: const EdgeInsets.all(16),
          child: Text(_hint ?? '只提取设备序列号，不打开二维码链接。')),
      TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('返回手动填写')),
    ])),
  );
}
