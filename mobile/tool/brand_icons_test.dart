// Renders the app-icon source images from BrandIcon, so the launcher icon and
// favicon always match the in-app logo. Regenerate after changing the logo:
//
//   flutter test tool/brand_icons_test.dart --update-goldens
//   dart run flutter_launcher_icons
//
// Lives in tool/ so it doesn't run with the regular test suite.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:newspal/app/theme.dart';
import 'package:newspal/widgets/brand_wordmark.dart';

const _size = 1024.0;

Future<void> _render(WidgetTester tester, {required Color? background, required double logoFraction, required String file}) async {
  tester.view.physicalSize = const Size(_size, _size);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(
    Directionality(
      textDirection: TextDirection.ltr,
      child: RepaintBoundary(
        key: const ValueKey('icon'),
        child: Container(
          width: _size,
          height: _size,
          color: background,
          alignment: Alignment.center,
          child: BrandIcon(size: _size * logoFraction),
        ),
      ),
    ),
  );
  await expectLater(find.byKey(const ValueKey('icon')), matchesGoldenFile('../assets/branding/$file'));
}

void main() {
  // Full icon (iOS, legacy Android, web): charcoal square, logo fills most of it.
  testWidgets('app icon', (tester) => _render(tester, background: Brand.charcoal, logoFraction: 0.78, file: 'app_icon.png'));

  // Android adaptive foreground: transparent, logo inside the 66% safe zone.
  testWidgets('adaptive foreground', (tester) => _render(tester, background: null, logoFraction: 0.56, file: 'app_icon_foreground.png'));
}
