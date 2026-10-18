import 'package:flutter/material.dart';

/// Brand colors (SPEC §9.1a).
abstract final class Brand {
  static const lime = Color(0xFFB6FF2E);
  static const charcoal = Color(0xFF23262F);
}

/// Dark-first: charcoal background, lime accent. Lime is never used as text on
/// light backgrounds, only as a fill with charcoal content on top.
final ColorScheme darkScheme = const ColorScheme(
  brightness: Brightness.dark,
  primary: Brand.lime,
  onPrimary: Brand.charcoal,
  secondary: Brand.lime,
  onSecondary: Brand.charcoal,
  error: Color(0xFFFF6B6B),
  onError: Brand.charcoal,
  surface: Brand.charcoal,
  onSurface: Color(0xFFF2F4F7),
  surfaceContainer: Color(0xFF2E323C),
  surfaceContainerHighest: Color(0xFF373C47),
  onSurfaceVariant: Color(0xFFA3A9B6),
  outline: Color(0xFF3A3F4B),
);

final ColorScheme lightScheme = const ColorScheme(
  brightness: Brightness.light,
  primary: Brand.lime,
  onPrimary: Brand.charcoal,
  secondary: Brand.charcoal,
  onSecondary: Color(0xFFFFFFFF),
  error: Color(0xFFC62828),
  onError: Color(0xFFFFFFFF),
  surface: Color(0xFFF7F8F4),
  onSurface: Brand.charcoal,
  surfaceContainer: Color(0xFFFFFFFF),
  surfaceContainerHighest: Color(0xFFECEEE8),
  onSurfaceVariant: Color(0xFF5B6170),
  outline: Color(0xFFD9DCE3),
);

ThemeData buildTheme(ColorScheme scheme) {
  final isDark = scheme.brightness == Brightness.dark;
  // Text-coloured accent: lime reads well on charcoal, but not on white.
  final accentText = isDark ? Brand.lime : Brand.charcoal;
  return ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    scaffoldBackgroundColor: scheme.surface,
    appBarTheme: AppBarTheme(
      backgroundColor: scheme.surface,
      foregroundColor: scheme.onSurface,
      elevation: 0,
      // Material 3 tints a scrolled-under app bar with the primary color, which
      // turns charcoal olive; keep it flat.
      scrolledUnderElevation: 0,
      surfaceTintColor: Colors.transparent,
      centerTitle: false,
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: Brand.lime,
        foregroundColor: Brand.charcoal,
        disabledBackgroundColor: scheme.outline,
        minimumSize: const Size.fromHeight(52),
        textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        foregroundColor: accentText,
        textStyle: TextStyle(
          fontWeight: FontWeight.w600,
          decoration: isDark ? null : TextDecoration.underline,
          decorationColor: Brand.lime,
          decorationThickness: 2,
        ),
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: scheme.surfaceContainer,
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(12),
        borderSide: BorderSide(color: scheme.outline),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(12),
        borderSide: BorderSide(color: scheme.outline),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(12),
        borderSide: BorderSide(color: isDark ? Brand.lime : Brand.charcoal, width: 2),
      ),
      floatingLabelStyle: TextStyle(color: accentText),
    ),
    chipTheme: ChipThemeData(
      selectedColor: Brand.lime,
      backgroundColor: scheme.surfaceContainer,
      side: BorderSide(color: scheme.outline),
      labelStyle: TextStyle(color: scheme.onSurface, fontWeight: FontWeight.w500),
      secondaryLabelStyle: const TextStyle(color: Brand.charcoal, fontWeight: FontWeight.w600),
      checkmarkColor: Brand.charcoal,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
    ),
    navigationBarTheme: NavigationBarThemeData(
      backgroundColor: scheme.surfaceContainer,
      indicatorColor: Brand.lime,
      iconTheme: WidgetStateProperty.resolveWith(
        (states) => IconThemeData(
          color: states.contains(WidgetState.selected) ? Brand.charcoal : scheme.onSurfaceVariant,
        ),
      ),
    ),
    progressIndicatorTheme: ProgressIndicatorThemeData(color: isDark ? Brand.lime : Brand.charcoal),
    snackBarTheme: SnackBarThemeData(
      backgroundColor: isDark ? scheme.surfaceContainerHighest : Brand.charcoal,
      contentTextStyle: TextStyle(color: isDark ? scheme.onSurface : Colors.white),
      actionTextColor: Brand.lime,
      behavior: SnackBarBehavior.floating,
    ),
  );
}
