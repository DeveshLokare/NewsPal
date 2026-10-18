import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/theme.dart';
import '../../core/api_error.dart';
import '../auth/auth_controller.dart';
import '../auth/auth_form.dart' show ButtonSpinner;
import '../feed/feed_controller.dart';
import 'category_repository.dart';

enum PickerMode {
  /// First run after registering; continuing lands on the feed.
  onboarding,

  /// From Settings; saving goes back.
  edit,
}

const _icons = <String, IconData>{
  'technology': Icons.memory,
  'business': Icons.trending_up,
  'sports': Icons.sports_soccer,
  'science': Icons.science_outlined,
  'health': Icons.favorite_border,
  'entertainment': Icons.movie_outlined,
  'world': Icons.public,
  'politics': Icons.account_balance_outlined,
};

/// Pick at least one category (US-4, US-5).
class CategoryPickerPage extends ConsumerStatefulWidget {
  const CategoryPickerPage({super.key, required this.mode});

  final PickerMode mode;

  @override
  ConsumerState<CategoryPickerPage> createState() => _CategoryPickerPageState();
}

class _CategoryPickerPageState extends ConsumerState<CategoryPickerPage> {
  final Set<String> _selected = {};
  bool _loadingMine = false;
  bool _saving = false;
  String? _error;

  bool get _onboarding => widget.mode == PickerMode.onboarding;

  @override
  void initState() {
    super.initState();
    if (!_onboarding) _loadMine();
  }

  Future<void> _loadMine() async {
    setState(() => _loadingMine = true);
    try {
      final mine = await ref.read(categoryRepositoryProvider).mine();
      if (mounted) setState(() => _selected.addAll(mine));
    } on ApiError catch (e) {
      if (mounted) setState(() => _error = e.message);
    } finally {
      if (mounted) setState(() => _loadingMine = false);
    }
  }

  Future<void> _save() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final catalog = ref.read(allCategoriesProvider).value ?? const <Category>[];
      // Keep catalog order so the saved list reads naturally.
      final ordered = [for (final c in catalog) if (_selected.contains(c.slug)) c.slug];
      await ref.read(categoryRepositoryProvider).saveMine(ordered);
      if (!mounted) return;
      // Chips and feeds depend on the followed categories.
      ref.invalidate(followedCategoriesProvider);
      ref.invalidate(feedControllerProvider);
      if (_onboarding) {
        ref.read(authControllerProvider.notifier).categoriesSaved();
      } else {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Categories updated')));
        context.pop();
      }
    } on ApiError catch (e) {
      if (mounted) setState(() => _error = e.message);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final catalog = ref.watch(allCategoriesProvider);
    final count = _selected.length;
    return Scaffold(
      appBar: _onboarding ? null : AppBar(title: const Text('Your categories')),
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 560),
            child: Padding(
              padding: const EdgeInsets.fromLTRB(24, 24, 24, 16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (_onboarding) ...[
                    Text('What do you want to read?',
                        style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w700)),
                    const SizedBox(height: 6),
                    Text('Pick at least one. You can change this anytime in Settings.',
                        style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
                    const SizedBox(height: 24),
                  ],
                  Expanded(
                    child: catalog.when(
                      loading: () => const Center(child: CircularProgressIndicator()),
                      error: (e, _) => _LoadError(
                        message: e is ApiError ? e.message : 'Couldn\'t load categories.',
                        onRetry: () => ref.invalidate(allCategoriesProvider),
                      ),
                      data: (categories) => _loadingMine
                          ? const Center(child: CircularProgressIndicator())
                          : SingleChildScrollView(
                              child: Wrap(
                                spacing: 10,
                                runSpacing: 12,
                                children: [
                                  for (final c in categories)
                                    FilterChip(
                                      key: ValueKey('category-${c.slug}'),
                                      avatar: Icon(
                                        _icons[c.slug] ?? Icons.label_outline,
                                        size: 18,
                                        color: _selected.contains(c.slug)
                                            ? Brand.charcoal
                                            : theme.colorScheme.onSurfaceVariant,
                                      ),
                                      showCheckmark: false,
                                      label: Text(c.name),
                                      // Text on lime is always charcoal (SPEC §9.1a).
                                      labelStyle: TextStyle(
                                        color: _selected.contains(c.slug) ? Brand.charcoal : theme.colorScheme.onSurface,
                                        fontWeight: _selected.contains(c.slug) ? FontWeight.w600 : FontWeight.w500,
                                      ),
                                      selected: _selected.contains(c.slug),
                                      onSelected: _saving
                                          ? null
                                          : (on) => setState(() => on ? _selected.add(c.slug) : _selected.remove(c.slug)),
                                      labelPadding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
                                    ),
                                ],
                              ),
                            ),
                    ),
                  ),
                  if (_error != null)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: Text(_error!, style: TextStyle(color: theme.colorScheme.error)),
                    ),
                  FilledButton(
                    onPressed: (count == 0 || _saving || !catalog.hasValue) ? null : _save,
                    child: _saving
                        ? const ButtonSpinner()
                        : Text(_onboarding
                            ? (count == 0 ? 'Pick at least one' : 'Continue ($count)')
                            : 'Save'),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _LoadError extends StatelessWidget {
  const _LoadError({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(message, textAlign: TextAlign.center),
          const SizedBox(height: 8),
          TextButton(onPressed: onRetry, child: const Text('Try again')),
        ],
      ),
    );
  }
}
