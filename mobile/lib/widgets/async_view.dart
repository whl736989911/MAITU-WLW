import 'package:flutter/material.dart';

import '../core/api_client.dart';

/// Standard loading / error / empty scaffolding for a `FutureBuilder`-driven
/// page section. An [ApiError] is shown with its code and request id, plus a
/// retry action that re-invokes [onRetry].
class AsyncSection<T> extends StatelessWidget {
  const AsyncSection({
    super.key,
    required this.future,
    required this.builder,
    this.onRetry,
    this.emptyHint = '暂无数据',
  });

  final Future<T> future;
  final Widget Function(T data) builder;
  final Future<void> Function()? onRetry;
  final String emptyHint;

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<T>(
      future: future,
      builder: (context, snapshot) {
        if (snapshot.connectionState == ConnectionState.waiting) {
          return const Center(child: Padding(padding: EdgeInsets.all(32), child: CircularProgressIndicator()));
        }
        if (snapshot.hasError) {
          final error = snapshot.error;
          final message = error is ApiError
              ? error.toString()
              : (error?.toString() ?? '加载失败');
          return _ErrorBox(message: message, onRetry: onRetry);
        }
        final data = snapshot.data;
        if (data == null) {
          return Center(child: Text(emptyHint, style: const TextStyle(color: Colors.grey)));
        }
        return builder(data);
      },
    );
  }
}

class _ErrorBox extends StatelessWidget {
  const _ErrorBox({required this.message, this.onRetry});

  final String message;
  final Future<void> Function()? onRetry;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(message, textAlign: TextAlign.center),
          if (onRetry != null)
            Padding(
              padding: const EdgeInsets.only(top: 12),
              child: OutlinedButton.icon(
                onPressed: () => onRetry!(),
                icon: const Icon(Icons.refresh, size: 18),
                label: const Text('重试'),
              ),
            ),
        ],
      ),
    );
  }
}
