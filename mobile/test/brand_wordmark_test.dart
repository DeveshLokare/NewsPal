import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:newspal/app/theme.dart';
import 'package:newspal/widgets/brand_wordmark.dart';

void main() {
  Future<Color?> palColor(WidgetTester tester, ColorScheme scheme) async {
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(scheme),
      home: const Scaffold(body: Center(child: BrandWordmark())),
    ));
    final rich = tester.widget<RichText>(find.descendant(of: find.byType(BrandWordmark), matching: find.byType(RichText)));
    Color? color;
    rich.text.visitChildren((span) {
      if (span is TextSpan && span.text == 'Pal') {
        color = span.style?.color;
        return false;
      }
      return true;
    });
    return color;
  }

  testWidgets('"Pal" is lime on dark surfaces', (tester) async {
    expect(await palColor(tester, darkScheme), Brand.lime);
  });

  testWidgets('"Pal" is never lime text on light surfaces', (tester) async {
    expect(await palColor(tester, lightScheme), lightScheme.onSurfaceVariant);
  });

  testWidgets('announces as NewsPal and can hide the icon', (tester) async {
    await tester.pumpWidget(const MaterialApp(home: BrandWordmark(showIcon: false)));
    expect(find.bySemanticsLabel('NewsPal'), findsOneWidget);
    expect(find.byType(BrandIcon), findsNothing);
  });
}
