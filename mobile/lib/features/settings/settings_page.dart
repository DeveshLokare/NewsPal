import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/router.dart';
import '../auth/auth_controller.dart';

class SettingsPage extends ConsumerWidget {
  const SettingsPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authControllerProvider).value;
    final email = auth is SignedIn ? auth.email : '';
    final hasQuestion = auth is SignedIn && auth.hasSecurityQuestion;
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: const Text('Settings')),
      body: ListView(
        children: [
          ListTile(
            leading: const Icon(Icons.person_outline),
            title: const Text('Signed in as'),
            subtitle: Text(email),
          ),
          const Divider(),
          ListTile(
            leading: const Icon(Icons.tune),
            title: const Text('Your categories'),
            subtitle: const Text('Choose what shows up in your feed'),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => context.go(Routes.editCategories),
          ),
          ListTile(
            leading: const Icon(Icons.shield_outlined),
            title: const Text('Security question'),
            subtitle: Text(hasQuestion ? 'Used to reset your password' : 'Not set yet: needed to reset your password'),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => context.go(Routes.securityQuestion),
          ),
          const Divider(),
          ListTile(
            leading: Icon(Icons.logout, color: theme.colorScheme.error),
            title: Text('Log out', style: TextStyle(color: theme.colorScheme.error)),
            onTap: () async {
              final confirmed = await showDialog<bool>(context: context, builder: (_) => const LogoutDialog());
              if (confirmed == true) {
                await ref.read(authControllerProvider.notifier).signOut();
              }
            },
          ),
        ],
      ),
    );
  }
}

/// Asks before signing out (US-3). Pops `true` to log out; anything else
/// (Cancel, back, tapping outside) leaves the user signed in.
class LogoutDialog extends StatelessWidget {
  const LogoutDialog({super.key});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Log out?'),
      content: const Text("You'll need to log in again to see your feed."),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('Cancel')),
        FilledButton(
          style: FilledButton.styleFrom(
            backgroundColor: scheme.error,
            foregroundColor: scheme.onError,
            // The theme makes filled buttons full width for forms; not in a dialog.
            minimumSize: const Size(88, 40),
          ),
          onPressed: () => Navigator.of(context).pop(true),
          child: const Text('Log out'),
        ),
      ],
    );
  }
}
