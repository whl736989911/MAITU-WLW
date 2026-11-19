import 'package:flutter/material.dart';

/// Closed label/color maps for platform status codes. Unknown codes are shown
/// as "未知状态（<code>）", never as a fabricated plausible state.
const Map<String, String> customerStatusLabels = <String, String>{
  'FIRE': '报警',
  'OFFLINE': '离线',
  'DISABLED': '禁用',
  'SHIELDED': '屏蔽',
  'FAULT': '故障',
  'ONLINE': '在线',
  'UNACTIVATED': '未激活',
  'UNKNOWN': '状态未确认',
};

const Map<String, String> onlineStatusLabels = <String, String>{
  'ONLINE': '在线',
  'OFFLINE': '离线',
  'UNACTIVATED': '未激活',
  'UNKNOWN': '状态未确认',
};

const Map<String, String> physicalStateLabels = <String, String>{
  'FIRE': '火警',
  'NORMAL': '正常',
};

const Map<String, String> responseStateLabels = <String, String>{
  'UNHANDLED': '待复核',
  'HANDLED': '已处理',
};

const Map<String, String> conclusionLabels = <String, String>{
  'TRUE_FIRE': '真实火警',
  'FALSE_ALARM': '误报',
  'TEST': '测试',
  'DEVICE_ALARM': '设备报警',
};

/// The four conclusions the platform accepts for alarm review.
const List<String> conclusions = <String>[
  'TRUE_FIRE',
  'FALSE_ALARM',
  'TEST',
  'DEVICE_ALARM',
];

String statusLabel(String? code, Map<String, String> labels) {
  if (code == null || code.isEmpty) return '未上报';
  return labels[code] ?? '未知状态（$code）';
}

Color statusColor(String? code) {
  switch (code) {
    case 'FIRE':
    case 'UNHANDLED':
      return const Color(0xFFD32F2F); // red
    case 'FAULT':
    case 'SHIELDED':
      return const Color(0xFFF57C00); // amber
    case 'ONLINE':
    case 'HANDLED':
    case 'NORMAL':
      return const Color(0xFF2E7D32); // green
    case 'OFFLINE':
    case 'DISABLED':
    case 'UNACTIVATED':
      return const Color(0xFF757575); // grey
    default:
      return const Color(0xFF757575);
  }
}

class StatusBadge extends StatelessWidget {
  const StatusBadge({super.key, required this.label, this.code});

  final String label;
  final String? code;

  @override
  Widget build(BuildContext context) {
    final color = statusColor(code);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: color,
          fontSize: 12,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}
