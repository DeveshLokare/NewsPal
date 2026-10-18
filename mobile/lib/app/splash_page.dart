import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../features/auth/auth_controller.dart';
import '../widgets/brand_wordmark.dart';

/// Shown while the stored session is checked; the router moves on when done.
class SplashPage extends ConsumerWidget {
  const SplashPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authControllerProvider);
    return Scaffold(
      body: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const BrandWordmark(size: 40),
            const SizedBox(height: 32),
            if (auth.hasError && !auth.hasValue) ...[
              const Text("Couldn't start NewsPal."),
              const SizedBox(height: 12),
              TextButton(
                onPressed: () => ref.invalidate(authControllerProvider),
                child: const Text('Try again'),
              ),
            ] else
              const SizedBox.square(dimension: 28, child: CircularProgressIndicator(strokeWidth: 3)),
          ],
        ),
      ),
    );
  }
}
