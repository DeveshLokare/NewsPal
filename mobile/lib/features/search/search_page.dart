import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/router.dart';
import '../categories/category_repository.dart';
import '../feed/article_card.dart';
import 'search_controller.dart';

/// Search the last 7 days of stored news, every category (US-17).
class SearchPage extends ConsumerStatefulWidget {
  const SearchPage({super.key});

  @override
  ConsumerState<SearchPage> createState() => _SearchPageState();
}

class _SearchPageState extends ConsumerState<SearchPage> {
  late final _text = TextEditingController(text: ref.read(searchControllerProvider).query);

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  void _useRecent(String query) {
    _text.value = TextEditingValue(text: query, selection: TextSelection.collapsed(offset: query.length));
    ref.read(searchControllerProvider.notifier).submit(query);
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(searchControllerProvider);
    final controller = ref.read(searchControllerProvider.notifier);
    final categories = ref.watch(allCategoriesProvider).value ?? const <Category>[];
    final names = {for (final c in categories) c.slug: c.name};
    return Scaffold(
      appBar: AppBar(
        titleSpacing: 0,
        title: TextField(
          key: const ValueKey('search-field'),
          controller: _text,
          autofocus: true,
          textInputAction: TextInputAction.search,
          decoration: InputDecoration(
            hintText: 'Search news',
            border: InputBorder.none,
            filled: false,
            suffixIcon: ValueListenableBuilder<TextEditingValue>(
              valueListenable: _text,
              builder: (_, value, _) => value.text.isEmpty
                  ? const SizedBox.shrink()
                  : IconButton(
                      tooltip: 'Clear',
                      icon: const Icon(Icons.close),
                      onPressed: () {
                        _text.clear();
                        controller.queryChanged('');
                      },
                    ),
            ),
          ),
          onChanged: controller.queryChanged,
          onSubmitted: (value) => controller.submit(value),
        ),
      ),
      body: Column(
        children: [
          if (state.loading) const LinearProgressIndicator(minHeight: 2) else const SizedBox(height: 2),
          Expanded(child: _Results(state: state, names: names, onRecent: _useRecent)),
        ],
      ),
    );
  }
}

class _Results extends ConsumerWidget {
  const _Results({required this.state, required this.names, required this.onRecent});

  final SearchState state;
  final Map<String, String> names;
  final ValueChanged<String> onRecent;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final controller = ref.read(searchControllerProvider.notifier);
    final theme = Theme.of(context);
    final muted = theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant);

    if (!state.searched) {
      return ListView(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
        children: [
          Text('Search news from the last 7 days, across every category.', style: muted),
          if (state.recent.isNotEmpty) ...[
            const SizedBox(height: 20),
            Text('Recent searches', style: theme.textTheme.labelLarge),
            const SizedBox(height: 4),
            for (final query in state.recent)
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: const Icon(Icons.history),
                title: Text(query),
                onTap: () => onRecent(query),
              ),
          ],
        ],
      );
    }
    if (state.items.isEmpty) {
      final message = state.error ?? state.notice ?? (state.loading ? null : 'No results for "${state.query.trim()}"');
      if (message == null) return const SizedBox.shrink();
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(state.error != null ? Icons.cloud_off_outlined : Icons.search_off,
                  size: 48, color: theme.colorScheme.onSurfaceVariant),
              const SizedBox(height: 16),
              Text(message, textAlign: TextAlign.center, style: muted),
              if (state.error != null)
                TextButton(onPressed: () => controller.submit(), child: const Text('Try again')),
            ],
          ),
        ),
      );
    }
    return ListView.builder(
      padding: const EdgeInsets.only(top: 4, bottom: 24),
      itemCount: state.items.length + 1,
      itemBuilder: (context, index) {
        if (index == state.items.length) {
          if (state.error != null) {
            return Padding(
              padding: const EdgeInsets.all(16),
              child: Column(children: [
                Text(state.error!, style: muted, textAlign: TextAlign.center),
                TextButton(onPressed: controller.loadMore, child: const Text('Try again')),
              ]),
            );
          }
          if (state.notice != null) {
            return Padding(padding: const EdgeInsets.all(16), child: Text(state.notice!, style: muted, textAlign: TextAlign.center));
          }
          if (state.hasMore) {
            // Built when it scrolls into view: fetch the next page.
            WidgetsBinding.instance.addPostFrameCallback((_) => controller.loadMore());
            return const Padding(padding: EdgeInsets.all(16), child: Center(child: CircularProgressIndicator()));
          }
          return const SizedBox(height: 8);
        }
        final article = state.items[index];
        return ArticleCard(
          key: ValueKey(article.url),
          article: article,
          categoryName: names[article.category],
          onTap: () {
            controller.remember();
            context.push(Routes.article, extra: article);
          },
        );
      },
    );
  }
}
