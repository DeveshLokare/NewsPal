import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/router.dart';
import '../../app/theme.dart';
import '../../core/api_client.dart';
import 'auth_controller.dart';

/// "Not now" on the banner, until the app restarts or another user signs in.
final securityBannerDismissedProvider =
    NotifierProvider<SecurityBannerDismissed, bool>(SecurityBannerDismissed.new);

class SecurityBannerDismissed extends Notifier<bool> {
  @override
  bool build() {
    ref.watch(sessionTokenProvider);
    return false;
  }

  void dismiss() => state = true;
}

/// Asks accounts from before security questions to set one (SPEC-v2 §7.6).
class SecurityQuestionBanner extends ConsumerWidget {
  const SecurityQuestionBanner({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authControllerProvider).value;
    final dismissed = ref.watch(securityBannerDismissedProvider);
    if (auth is! SignedIn || auth.hasSecurityQuestion || dismissed) return const SizedBox.shrink();
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Card(
      key: const ValueKey('security-banner'),
      margin: const EdgeInsets.fromLTRB(16, 8, 16, 4),
      color: scheme.surfaceContainer,
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: const BorderSide(color: Brand.lime, width: 1.5),
      ),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 14, 8, 6),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(Icons.shield_outlined, color: scheme.onSurface),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    'Set a security question so you can reset your password if you forget it.',
                    style: theme.textTheme.bodyMedium,
                  ),
                ),
              ],
            ),
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                TextButton(
                  onPressed: () => ref.read(securityBannerDismissedProvider.notifier).dismiss(),
                  child: Text('Not now', style: TextStyle(color: scheme.onSurfaceVariant)),
                ),
                TextButton(
                  onPressed: () => context.go(Routes.securityQuestion),
                  child: Text('Set it', style: TextStyle(color: scheme.onSurface, fontWeight: FontWeight.w700)),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
