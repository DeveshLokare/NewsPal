import 'package:flutter/foundation.dart';

/// Backend base URL. Override with `--dart-define=API_BASE_URL=https://...`.
///
/// Defaults: the Android emulator reaches the host machine at 10.0.2.2; web and
/// iOS simulator use localhost.
String get apiBaseUrl {
  const fromEnv = String.fromEnvironment('API_BASE_URL');
  if (fromEnv.isNotEmpty) return fromEnv;
  if (!kIsWeb && defaultTargetPlatform == TargetPlatform.android) {
    return 'http://10.0.2.2:8080';
  }
  return 'http://localhost:8080';
}
