/// Time handling for platform timestamps.
///
/// The backend serializes instants as ISO-8601 with an offset (Asia/Shanghai)
/// or `Z`. A missing/unparsable timestamp must never be fabricated into a
/// "now" value: callers render `--` and keep the source fact unknown.
String? formatDateTime(String? iso) {
  if (iso == null || iso.trim().isEmpty) return null;
  final parsed = DateTime.tryParse(iso);
  if (parsed == null) return null;
  final local = parsed.toLocal();
  String two(int n) => n.toString().padLeft(2, '0');
  return '${local.year}-${two(local.month)}-${two(local.day)} '
      '${two(local.hour)}:${two(local.minute)}:${two(local.second)}';
}

/// Never returns a fabricated timestamp: null stays null.
String orUnknown(String? formatted) => formatted ?? '--';
