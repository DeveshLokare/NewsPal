import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/router.dart';
import '../../app/theme.dart';
import '../../core/api_error.dart';
import '../../core/time_format.dart';
import '../../widgets/brand_wordmark.dart';
import '../auth/security_question_banner.dart';
import '../categories/category_repository.dart';
import 'article_card.dart';
import 'feed_controller.dart';

/// The selected chip: `null` = All.
final selectedCategoryProvider = NotifierProvider<SelectedCategory, String?>(SelectedCategory.new);

class SelectedCategory extends Notifier<String?> {
  @override
  String? build() => null;

  void select(String? slug) => state = slug;
}

/// The feed (US-6, US-7, US-9): category chips over an infinite list.
class FeedPage extends ConsumerWidget {
  const FeedPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final followed = ref.watch(followedCategoriesProvider).value ?? const <Category>[];
    final selected = ref.watch(selectedCategoryProvider);
    // A category the user stopped following falls back to All.
    final effective = followed.any((c) => c.slug == selected) ? selected : null;
    final names = {for (final c in followed) c.slug: c.name};
    return Scaffold(
      appBar: AppBar(
        title: const BrandWordmark(size: 22),
        titleSpacing: 16,
        actions: [
          IconButton(
            key: const ValueKey('open-search'),
            tooltip: 'Search',
            icon: const Icon(Icons.search),
            onPressed: () => context.push(Routes.search),
          ),
          const SizedBox(width: 4),
        ],
      ),
      body: Column(
        children: [
          const SecurityQuestionBanner(),
          _CategoryChips(categories: followed, selected: effective),
          Expanded(child: FeedList(key: ValueKey(effective), category: effective, categoryNames: names)),
        ],
      ),
    );
  }
}

class _CategoryChips extends ConsumerWidget {
  const _CategoryChips({required this.categories, required this.selected});

  final List<Category> categories;
  final String? selected;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scheme = Theme.of(context).colorScheme;
    Widget chip(String? slug, String label) {
      final isSelected = slug == selected;
      return Padding(
        padding: const EdgeInsets.only(right: 8),
        child: ChoiceChip(
          key: ValueKey('chip-${slug ?? 'all'}'),
          label: Text(label),
          selected: isSelected,
          showCheckmark: false,
          // Text on lime is always charcoal (SPEC §9.1a).
          labelStyle: TextStyle(
            color: isSelected ? Brand.charcoal : scheme.onSurface,
            fontWeight: isSelected ? FontWeight.w700 : FontWeight.w500,
          ),
          onSelected: (_) => ref.read(selectedCategoryProvider.notifier).select(slug),
        ),
      );
    }

    return SizedBox(
      height: 52,
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.fromLTRB(16, 6, 8, 6),
        children: [
          chip(null, 'All'),
          for (final c in categories) chip(c.slug, c.name),
        ],
      ),
    );
  }
}

/// The paged list for one chip.
class FeedList extends ConsumerWidget {
  const FeedList({super.key, required this.category, required this.categoryNames});

  final String? category;
  final Map<String, String> categoryNames;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final provider = feedControllerProvider(category);
    final feed = ref.watch(provider);
    final controller = ref.read(provider.notifier);

    return RefreshIndicator(
      color: Brand.charcoal,
      backgroundColor: Brand.lime,
      onRefresh: controller.refresh,
      child: feed.when(
        skipLoadingOnRefresh: true,
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => _Message(
          icon: Icons.cloud_off_outlined,
          title: "Couldn't load your feed",
          body: e is ApiError ? e.message : 'Something went wrong.',
          action: TextButton(onPressed: controller.refresh, child: const Text('Try again')),
        ),
        data: (state) {
          if (state.items.isEmpty && state.phase != FeedPhase.stored) {
            return _EmptyFeed(state: state, controller: controller);
          }
          return ListView.builder(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.only(bottom: 24),
            itemCount: state.items.length + 2,
            itemBuilder: (context, index) {
              if (index == 0) return _UpdatedLine(state.lastRefreshedAt);
              if (index == state.items.length + 1) return _Footer(state: state, controller: controller);
              final article = state.items[index - 1];
              return ArticleCard(
                key: ValueKey(article.url),
                article: article,
                categoryName: category == null ? categoryNames[article.category] : null,
                onTap: () => context.push(Routes.article, extra: article),
              );
            },
          );
        },
      ),
    );
  }
}

class _UpdatedLine extends StatelessWidget {
  const _UpdatedLine(this.at);

  final DateTime? at;

  @override
  Widget build(BuildContext context) {
    if (at == null) return const SizedBox(height: 4);
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 2, 20, 6),
      child: Text(
        'Updated ${timeAgo(at!)}',
        style: theme.textTheme.labelSmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
      ),
    );
  }
}

/// End of the list: loads the next page when it scrolls into view, or offers
/// older news, a retry, or says we're done.
class _Footer extends StatelessWidget {
  const _Footer({required this.state, required this.controller});

  final FeedState state;
  final FeedController controller;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final muted = theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant);

    if (state.loadMoreError != null) {
      return _FooterBox(children: [
        Text(state.loadMoreError!, style: muted, textAlign: TextAlign.center),
        TextButton(
          onPressed: state.phase == FeedPhase.storedDone ? controller.loadOlder : controller.loadMore,
          child: const Text('Try again'),
        ),
      ]);
    }
    if (state.loadingMore) {
      return const _FooterBox(children: [CircularProgressIndicator()]);
    }
    switch (state.phase) {
      case FeedPhase.stored:
      case FeedPhase.older:
        // Built when it scrolls into view: fetch the next page.
        WidgetsBinding.instance.addPostFrameCallback((_) => controller.loadMore());
        return const _FooterBox(children: [CircularProgressIndicator()]);
      case FeedPhase.storedDone:
        return _FooterBox(children: [
          Text("That's everything from the past week.", style: muted, textAlign: TextAlign.center),
          const SizedBox(height: 8),
          if (controller.canLoadOlder)
            _LoadOlderButton(onPressed: controller.loadOlder)
          else
            Text('Pick a category above to load older news.', style: muted, textAlign: TextAlign.center),
        ]);
      case FeedPhase.done:
        return _FooterBox(children: [
          Text(state.notice ?? "You're all caught up.", style: muted, textAlign: TextAlign.center),
        ]);
    }
  }
}

class _LoadOlderButton extends StatelessWidget {
  const _LoadOlderButton({required this.onPressed});

  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return OutlinedButton.icon(
      onPressed: onPressed,
      icon: const Icon(Icons.history),
      label: const Text('Load older news'),
      style: OutlinedButton.styleFrom(
        foregroundColor: Theme.of(context).colorScheme.onSurface,
        side: const BorderSide(color: Brand.lime, width: 1.5),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 12),
      ),
    );
  }
}

class _FooterBox extends StatelessWidget {
  const _FooterBox({required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 20),
      child: Column(mainAxisSize: MainAxisSize.min, children: children),
    );
  }
}

class _EmptyFeed extends StatelessWidget {
  const _EmptyFeed({required this.state, required this.controller});

  final FeedState state;
  final FeedController controller;

  @override
  Widget build(BuildContext context) {
    final canOlder = controller.canLoadOlder && state.phase == FeedPhase.storedDone;
    return _Message(
      icon: Icons.newspaper_outlined,
      title: 'No news yet',
      body: state.notice ??
          "We're fetching the latest stories for your categories. Pull down to refresh in a minute.",
      action: state.loadingMore
          ? const Padding(padding: EdgeInsets.all(8), child: CircularProgressIndicator())
          : (canOlder ? _LoadOlderButton(onPressed: controller.loadOlder) : null),
    );
  }
}

/// Centered icon + title + body, still scrollable so pull-to-refresh works.
class _Message extends StatelessWidget {
  const _Message({required this.icon, required this.title, required this.body, this.action});

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
