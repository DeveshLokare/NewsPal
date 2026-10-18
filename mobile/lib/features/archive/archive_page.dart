import 'package:flutter/material.dart';

import '../../widgets/coming_soon.dart';

/// Placeholder until M6 (archive list).
class ArchivePage extends StatelessWidget {
  const ArchivePage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Archive')),
      body: const ComingSoon(
        icon: Icons.bookmark_border,
        title: 'Nothing archived yet',
        message: 'Articles you bookmark will be kept here.',
      ),
    );
  }
}
