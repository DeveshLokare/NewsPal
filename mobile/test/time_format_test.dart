import 'package:flutter_test/flutter_test.dart';
import 'package:newspal/core/time_format.dart';

void main() {
  final now = DateTime.utc(2026, 10, 19, 12);

  test('relative times', () {
    expect(timeAgo(now.subtract(const Duration(seconds: 20)), now: now), 'just now');
    expect(timeAgo(now.subtract(const Duration(minutes: 5)), now: now), '5m ago');
    expect(timeAgo(now.subtract(const Duration(hours: 3)), now: now), '3h ago');
    expect(timeAgo(now.subtract(const Duration(days: 2)), now: now), '2d ago');
  });

  test('older than a week shows the date, with the year only when different', () {
    expect(timeAgo(DateTime.utc(2026, 10, 1, 12), now: now), '1 Oct');
    expect(timeAgo(DateTime.utc(2025, 12, 24, 12), now: now), '24 Dec 2025');
  });
}
