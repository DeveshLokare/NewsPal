import 'package:flutter/material.dart';

import '../app/theme.dart';

/// The NewsPal logo: a lime folded newspaper page with headline lines, followed
/// by "News" + "Pal". "Pal" is lime on dark surfaces and muted grey on light
/// ones, since lime text is unreadable on white (SPEC §9.1a).
class BrandWordmark extends StatelessWidget {
  const BrandWordmark({super.key, this.size = 28, this.showIcon = true});

  /// Height of the text; the icon is drawn slightly taller.
  final double size;
  final bool showIcon;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final isDark = scheme.brightness == Brightness.dark;
    final style = TextStyle(fontSize: size, fontWeight: FontWeight.w700, letterSpacing: -0.5, height: 1.1);
    return Semantics(
      label: 'NewsPal',
      excludeSemantics: true,
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (showIcon) ...[
            BrandIcon(size: size * 1.35),
            SizedBox(width: size * 0.35),
          ],
          Text.rich(
            TextSpan(children: [
              TextSpan(text: 'News', style: style.copyWith(color: scheme.onSurface)),
              TextSpan(text: 'Pal', style: style.copyWith(color: isDark ? Brand.lime : scheme.onSurfaceVariant)),
            ]),
          ),
        ],
      ),
    );
  }
}

/// The folded-page mark on its own (app icon, splash).
class BrandIcon extends StatelessWidget {
  const BrandIcon({super.key, this.size = 40});

  final double size;

  @override
  Widget build(BuildContext context) {
    return CustomPaint(size: Size.square(size), painter: const _FoldedPagePainter());
  }
}

class _FoldedPagePainter extends CustomPainter {
  const _FoldedPagePainter();

  /// Darker lime for the folded corner, so the fold reads on any background.
  static const _fold = Color(0xFF7FB81C);

  @override
  void paint(Canvas canvas, Size size) {
    // Drawn on a 40×40 grid (matches the design mockup), then scaled.
    final s = size.width / 40;
    canvas.scale(s, s);

    final page = Path()
      ..moveTo(6, 4)
      ..lineTo(26, 4)
      ..lineTo(34, 12)
      ..lineTo(34, 36)
      ..lineTo(6, 36)
      ..close();
    canvas.drawPath(page, Paint()..color = Brand.lime);

    final fold = Path()
      ..moveTo(26, 4)
      ..lineTo(26, 12)
      ..lineTo(34, 12)
      ..close();
    canvas.drawPath(fold, Paint()..color = _fold);

    final ink = Paint()..color = Brand.charcoal;
    // Headline, then two body lines.
    canvas.drawRRect(RRect.fromLTRBR(11, 15, 25, 18, const Radius.circular(1.5)), ink);
    canvas.drawRRect(RRect.fromLTRBR(11, 21, 29, 23.5, const Radius.circular(1.25)), ink);
    canvas.drawRRect(RRect.fromLTRBR(11, 26.5, 23, 29, const Radius.circular(1.25)), ink);
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}
