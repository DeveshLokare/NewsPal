import 'package:flutter/material.dart';

import '../../widgets/brand_wordmark.dart';
import '../../widgets/coming_soon.dart';

/// Placeholder until M5 (feed, article view, older news).
class FeedPage extends StatelessWidget {
  const FeedPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const BrandWordmark(size: 22)),
      body: const ComingSoon(
        icon: Icons.newspaper_outlined,
        title: 'Your feed is on its way',
        message: 'News from your categories will show up here.',
      ),
    );
  }
}
