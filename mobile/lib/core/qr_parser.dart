/// Locally extracts a device serial from a label. The label is never opened.
class QrSerial {
  const QrSerial(this.serial);
  final String serial;
}

class QrParseFailure implements Exception {
  const QrParseFailure(this.message);
  final String message;
  @override
  String toString() => message;
}

/// Public registration API serial constraint.
final RegExp _serialPattern = RegExp(r'^[\x21-\x7e]{1,200}$');

const _serialKeys = {'deviceserial', 'device_serial', 'serial', 'deviceid', 'device_id', 'sn'};
final _uriPrefix = RegExp(r'^[a-zA-Z][a-zA-Z0-9+.-]*:');
final _keyValue = RegExp(r'^([a-zA-Z_][a-zA-Z_0-9]*)[:=]\s*(.*)$');

/// Parses the raw QR text. Returns the extracted serial or throws
/// [QrParseFailure] with a user-facing reason.
QrSerial parseQrSerial(String raw) {
  final content = raw.trim();
  if (content.isEmpty || content.length > 4096) {
    throw const QrParseFailure('二维码内容为空或过长，请重新扫描机身标签');
  }
  String? serial;
  final uri = Uri.tryParse(content);
  if (uri != null && (uri.scheme == 'https' || uri.scheme == 'http') &&
      uri.host.isNotEmpty && uri.userInfo.isEmpty) {
    try {
      final fields = uri.queryParametersAll.entries
          .where((field) => _serialKeys.contains(field.key.toLowerCase()))
          .expand((field) => field.value)
          .toList();
      if (fields.length == 1) serial = fields.single;
    } on FormatException {
      // An invalid URL cannot supply a serial.
    } on ArgumentError {
      // Invalid percent encoding.
    }
  } else {
    final match = _keyValue.firstMatch(content);
    if (match != null && _serialKeys.contains(match.group(1)?.toLowerCase())) {
      serial = match.group(2);
    } else if (match == null &&
        !_uriPrefix.hasMatch(content) &&
        !content.startsWith('//') &&
        !content.contains('://')) {
      serial = content;
    }
  }
  if (serial == null || !_serialPattern.hasMatch(serial) ||
      serial.contains('://') || serial.codeUnits.any((unit) => unit <= 0x20)) {
    throw const QrParseFailure('未识别到唯一有效的序列号，请核对机身标签或手动填写');
  }
  return QrSerial(serial);
}
