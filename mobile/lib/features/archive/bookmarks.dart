import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../core/api_client.dart';
import '../../core/api_error.dart';
import '../feed/article.dart';
import 'archive_controller.dart';
import 'archive_repository.dart';

/// Archive state changed in this session, by URL. Feed items carry an
/// `archived` flag from when they were loaded; this overrides it, so a toggle
/// shows on the feed, the article screen and the archive without refetching.
final bookmarksProvider = NotifierProvider<Bookmarks, Map<String, bool>>(Bookmarks.new);

class Bookmarks extends Notifier<Map<String, bool>> {
  final Set<String> _pending = {};

  @override
  Map<String, bool> build() {
    // Per user: forget overrides when someone else signs in.
    ref.watch(sessionTokenProvider);
    return const {};
  }

  bool isArchived(Article article) => state[article.url] ?? article.archived;

  void set(String url, bool archived) => state = {...state, url: archived};

  /// Archive or un-archive (US-10). Updates the UI first; on failure reverts
  /// and throws [ApiError]. Taps while a request is in flight are ignored.
  Future<void> toggle(Article article) async {
    if (!_pending.add(article.url)) return;
    final was = isArchived(article);
    set(article.url, !was);
    try {
      final repo = ref.read(archiveRepositoryProvider);
      if (was) {
        await repo.removeByUrl(article.url);
      } else {
        await repo.add(article);
      }
      // The archive tab reloads next time it's shown.
      ref.invalidate(archiveControllerProvider);
    } on ApiError {
      set(article.url, was);
      rethrow;
    } finally {
      _pending.remove(article.url);
    }
  }
}

/// Bookmark icon for feed cards and the article screen.
class BookmarkButton extends ConsumerWidget {
  const BookmarkButton({super.key, required this.article, this.size = 24});

  final Article article;
  final double size;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    ref.watch(bookmarksProvider);
    final archived = ref.read(bookmarksProvider.notifier).isArchived(article);
    final scheme = Theme.of(context).colorScheme;
    // Lime reads on dark surfaces only; on light ones the filled icon is charcoal.
    final onColor = scheme.brightness == Brightness.dark ? Brand.lime : Brand.charcoal;
    return IconButton(
      key: ValueKey('bookmark-${article.url}'),
      tooltip: archived ? 'Remove from archive' : 'Save to archive',
      iconSize: size,
      icon: Icon(
        archived ? Icons.bookmark : Icons.bookmark_border,
        color: archived ? onColor : scheme.onSurfaceVariant,
      ),
      onPressed: () async {
        try {
          await ref.read(bookmarksProvider.notifier).toggle(article);
        } on ApiError {
          if (context.mounted) {
            ScaffoldMessenger.of(context)
              ..hideCurrentSnackBar()
              ..showSnackBar(const SnackBar(content: Text("Couldn't update your archive. Try again.")));
          }
        }
      },
    );
  }
}
