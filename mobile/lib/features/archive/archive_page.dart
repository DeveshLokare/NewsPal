import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/router.dart';
import '../../app/theme.dart';
import '../../core/api_error.dart';
import '../categories/category_repository.dart';
import '../feed/article_card.dart';
import 'archive_controller.dart';
import 'archive_repository.dart';

/// Archived articles (US-11, US-12): newest first; swipe or tap the bookmark to
/// remove, with an undo snackbar.
class ArchivePage extends ConsumerWidget {
  const ArchivePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final archive = ref.watch(archiveControllerProvider);
    final controller = ref.read(archiveControllerProvider.notifier);
    final names = {
      for (final c in ref.watch(allCategoriesProvider).value ?? const <Category>[]) c.slug: c.name,
    };

    return Scaffold(
      appBar: AppBar(title: const Text('Archive')),
      body: RefreshIndicator(
        color: Brand.charcoal,
        backgroundColor: Brand.lime,
        onRefresh: controller.refresh,
        child: archive.when(
          skipLoadingOnRefresh: true,
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (e, _) => _CenteredMessage(
            icon: Icons.cloud_off_outlined,
            title: "Couldn't load your archive",
            body: e is ApiError ? e.message : 'Something went wrong.',
            action: TextButton(onPressed: controller.refresh, child: const Text('Try again')),
          ),
          data: (state) {
            if (state.items.isEmpty) {
              return const _CenteredMessage(
                icon: Icons.bookmark_border,
                title: 'Nothing archived yet',
                body: 'Tap the bookmark on any story to keep it here, even after it leaves your feed.',
              );
            }
            return ListView.builder(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.only(top: 4, bottom: 24),
              itemCount: state.items.length + 1,
              itemBuilder: (context, index) {
                if (index == state.items.length) return _Footer(state: state, controller: controller);
                final item = state.items[index];
                return Dismissible(
                  key: ValueKey('archive-${item.id}'),
                  direction: DismissDirection.endToStart,
                  background: const _SwipeBackground(),
                  onDismissed: (_) => removeWithUndo(context, controller, item),
                  child: ArticleCard(
                    article: item.toArticle(),
                    categoryName: names[item.category],
                    onTap: () => context.push(Routes.article, extra: item.toArticle()),
                    trailing: IconButton(
                      key: ValueKey('unarchive-${item.id}'),
                      tooltip: 'Remove from archive',
                      iconSize: 22,
                      icon: Icon(
                        Icons.bookmark,
                        color: Theme.of(context).brightness == Brightness.dark ? Brand.lime : Brand.charcoal,
                      ),
                      onPressed: () => removeWithUndo(context, controller, item),
                    ),
                  ),
                );
              },
            );
          },
        ),
      ),
    );
  }
}

/// Removes [item] and offers "Undo" for 4 seconds (US-12).
@visibleForTesting
Future<void> removeWithUndo(BuildContext context, ArchiveController controller, ArchiveItem item) async {
  final messenger = ScaffoldMessenger.of(context);
  final int index;
  try {
    index = await controller.remove(item);
  } on ApiError {
    messenger
      ..hideCurrentSnackBar()
      ..showSnackBar(const SnackBar(content: Text("Couldn't remove it. Try again.")));
    return;
  }
  if (index < 0) return;
  messenger
    ..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(
      content: const Text('Removed from archive'),
      duration: const Duration(seconds: 4),
      // Auto-dismiss even though there's an action.
      persist: false,
      action: SnackBarAction(
        label: 'Undo',
        onPressed: () async {
          try {
            await controller.undoRemove(item, index);
          } on ApiError {
            messenger.showSnackBar(const SnackBar(content: Text("Couldn't restore it. Try again.")));
          }
        },
      ),
    ));
}

class _SwipeBackground extends StatelessWidget {
  const _SwipeBackground();

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      decoration: BoxDecoration(color: scheme.error, borderRadius: BorderRadius.circular(16)),
      alignment: Alignment.centerRight,
      padding: const EdgeInsets.only(right: 24),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text('Remove', style: TextStyle(color: scheme.onError, fontWeight: FontWeight.w700)),
          const SizedBox(width: 8),
          Icon(Icons.delete_outline, color: scheme.onError),
        ],
      ),
    );
  }
}

class _Footer extends StatelessWidget {
  const _Footer({required this.state, required this.controller});

  final ArchiveState state;
  final ArchiveController controller;

  @override
  Widget build(BuildContext context) {
    if (state.loadMoreError != null) {
      return Padding(
        padding: const EdgeInsets.all(20),
        child: Column(children: [
          Text(state.loadMoreError!, textAlign: TextAlign.center),
          TextButton(onPressed: controller.loadMore, child: const Text('Try again')),
        ]),
      );
    }
    if (state.cursor == null) return const SizedBox(height: 8);
    if (!state.loadingMore) {
      WidgetsBinding.instance.addPostFrameCallback((_) => controller.loadMore());
    }
    return const Padding(padding: EdgeInsets.all(20), child: Center(child: CircularProgressIndicator()));
  }
}

class _CenteredMessage extends StatelessWidget {
  const _CenteredMessage({required this.icon, required this.title, required this.body, this.action});

  final IconData icon;
  final String title;
  final String body;
  final Widget? action;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return LayoutBuilder(
      builder: (context, constraints) => SingleChildScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        child: ConstrainedBox(
          constraints: BoxConstraints(minHeight: constraints.maxHeight),
          child: Center(
            child: Padding(
              padding: const EdgeInsets.all(32),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(icon, size: 48, color: theme.colorScheme.onSurfaceVariant),
                  const SizedBox(height: 16),
                  Text(title, style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700)),
                  const SizedBox(height: 6),
                  Text(body,
                      textAlign: TextAlign.center,
                      style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
                  if (action != null) ...[const SizedBox(height: 16), action!],
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
