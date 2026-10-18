import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/api_client.dart';
import 'router.dart';
import 'theme.dart';

class NewsPalApp extends ConsumerWidget {
  const NewsPalApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return MaterialApp.router(
      title: 'NewsPal',
      debugShowCheckedModeBanner: false,
      theme: buildTheme(lightScheme),
      darkTheme: buildTheme(darkScheme),
      themeMode: ThemeMode.system,
      routerConfig: ref.watch(routerProvider),
      builder: (context, child) => Column(
        children: [
          Expanded(child: child ?? const SizedBox.shrink()),
          const ServerWakingBanner(),
        ],
      ),
    );
  }
}

/// Shown while a request has been pending for more than 3 seconds, which on
/// Render's free tier usually means the server is starting up.
class ServerWakingBanner extends ConsumerWidget {
  const ServerWakingBanner({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final waking = ref.watch(serverWakingProvider) > 0;
    if (!waking) return const SizedBox.shrink();
    return Material(
      color: Brand.charcoal,
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
          child: Row(
            children: [
              const SizedBox.square(
                dimension: 16,
                child: CircularProgressIndicator(strokeWidth: 2, color: Brand.lime),
              ),
              const SizedBox(width: 12),
              const Expanded(
                child: Text(
                  'Waking up the server… this can take up to a minute.',
                  style: TextStyle(color: Colors.white),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
