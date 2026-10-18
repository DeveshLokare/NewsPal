const _months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/// "just now", "5m ago", "3h ago", "2d ago", then "12 Oct" (or "12 Oct 2025"
/// for another year).
String timeAgo(DateTime time, {DateTime? now}) {
  final current = (now ?? DateTime.now()).toUtc();
  final at = time.toUtc();
  final diff = current.difference(at);
  if (diff.inMinutes < 1) return 'just now';
  if (diff.inMinutes < 60) return '${diff.inMinutes}m ago';
  if (diff.inHours < 24) return '${diff.inHours}h ago';
  if (diff.inDays < 7) return '${diff.inDays}d ago';
  final local = at.toLocal();
  final date = '${local.day} ${_months[local.month - 1]}';
  return local.year == current.toLocal().year ? date : '$date ${local.year}';
}
