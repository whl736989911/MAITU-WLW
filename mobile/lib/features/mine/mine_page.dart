import 'package:flutter/material.dart';

import '../../state/app_scope.dart';

class MinePage extends StatelessWidget {
  const MinePage({super.key});

  @override
  Widget build(BuildContext context) {
    final state = AppScope.of(context);
    final user = state.user;
    return ListView(padding: const EdgeInsets.all(16), children: [
      Card(child: ListTile(
        leading: const Icon(Icons.person_outline),
        title: Text(user?.name ?? '客户账号'),
        subtitle: Text(user?.username ?? ''),
      )),
      const SizedBox(height: 16),
      OutlinedButton.icon(onPressed: state.logout, icon: const Icon(Icons.logout), label: const Text('退出登录')),
    ]);
  }
}
