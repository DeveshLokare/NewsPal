import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:webview_flutter/webview_flutter.dart';

import '../../core/link_rules.dart';
import '../../core/time_format.dart';
import '../archive/bookmarks.dart';
import 'article.dart';

/// Whether articles open in an in-app WebView. On web, news sites usually
/// refuse to load inside an iframe, so a reading card opens a new tab instead.
final inAppWebViewProvider = Provider<bool>((ref) => !kIsWeb);

Future<void> openInBrowser(Uri uri) => launchUrl(uri, mode: LaunchMode.externalApplication);

/// US-8: read an article inside the app.
class ArticlePage extends ConsumerWidget {
  const ArticlePage({super.key, required this.article});

  final Article? article;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final article = this.article;
    if (article == null) {
      // Deep link or page reload without the article in memory.
      return Scaffold(
        appBar: AppBar(),
        body: const Center(child: Text('This article is no longer open. Go back to your feed.')),
      );
    }
    return ref.watch(inAppWebViewProvider) ? _ArticleWebView(article: article) : _ArticleCard(article: article);
  }
}

class _ArticleWebView extends StatefulWidget {
  const _ArticleWebView({required this.article});

  final Article article;

  @override
  State<_ArticleWebView> createState() => _ArticleWebViewState();
}

class _ArticleWebViewState extends State<_ArticleWebView> {
  late final WebViewController _controller;
  int _progress = 0;
  bool _failed = false;

  /// The page currently shown; links are judged against its site, so a
  /// publisher redirecting to its AMP or regional domain still works.
  late Uri _currentPage = Uri.parse(widget.article.url);

  /// Until the first page finishes, navigations are the site's own redirects,
  /// not taps, and stay in the WebView.
  bool _firstLoadDone = false;

  @override
  void initState() {
    super.initState();
    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setNavigationDelegate(NavigationDelegate(
        onProgress: (p) => setState(() => _progress = p),
        onPageStarted: (_) => setState(() => _failed = false),
        onPageFinished: (url) {
          _firstLoadDone = true;
          final uri = Uri.tryParse(url);
          if (uri != null && uri.hasScheme) _currentPage = uri;
        },
        onWebResourceError: (error) {
          if (error.isForMainFrame ?? true) setState(() => _failed = true);
        },
        onNavigationRequest: _decide,
      ))
      ..loadRequest(Uri.parse(widget.article.url));
  }

  NavigationDecision _decide(NavigationRequest request) {
    final target = Uri.tryParse(request.url);
    if (target == null) return NavigationDecision.prevent;
    final isWeb = target.scheme == 'http' || target.scheme == 'https';
    if (isWeb && (!_firstLoadDone || !request.isMainFrame)) return NavigationDecision.navigate;
    if (shouldOpenExternally(_currentPage, target)) {
      // Other sites, mailto:, tel:, app links: hand off; the article stays open.
      openInBrowser(target);
      return NavigationDecision.prevent;
    }
    return NavigationDecision.navigate;
  }

  Future<void> _back() async {
    if (await _controller.canGoBack()) {
      await _controller.goBack();
    } else if (mounted) {
      context.pop();
    }
  }

  @override
  Widget build(BuildContext context) {
    final article = widget.article;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _back();
      },
      child: Scaffold(
        appBar: AppBar(
          title: Text(article.source ?? 'Article', overflow: TextOverflow.ellipsis),
          actions: [
            BookmarkButton(article: article),
            IconButton(
              tooltip: 'Open in browser',
              icon: const Icon(Icons.open_in_new),
              onPressed: () => openInBrowser(_currentPage),
            ),
          ],
          bottom: _progress < 100 && !_failed
              ? PreferredSize(
                  preferredSize: const Size.fromHeight(3),
                  child: LinearProgressIndicator(value: _progress / 100, minHeight: 3),
                )
              : null,
        ),
        body: _failed
            ? _LoadFailed(
                onRetry: () {
                  setState(() => _failed = false);
                  _controller.loadRequest(_currentPage);
                },
                onOpenInBrowser: () => openInBrowser(_currentPage),
              )
            : WebViewWidget(controller: _controller),
      ),
    );
  }
}

class _LoadFailed extends StatelessWidget {
  const _LoadFailed({required this.onRetry, required this.onOpenInBrowser});

  final VoidCallback onRetry;
  final VoidCallback onOpenInBrowser;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.wifi_off, size: 48, color: theme.colorScheme.onSurfaceVariant),
            const SizedBox(height: 16),
            Text("Couldn't load this article", style: theme.textTheme.titleMedium),
            const SizedBox(height: 16),
            FilledButton(onPressed: onRetry, child: const Text('Try again')),
            const SizedBox(height: 8),
            TextButton(onPressed: onOpenInBrowser, child: const Text('Open in browser')),
          ],
        ),
      ),
    );
  }
}

/// Web fallback: the story's summary with a button that opens the full
/// article in a new tab.
class _ArticleCard extends StatelessWidget {
  const _ArticleCard({required this.article});

  final Article article;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final meta = [if (article.source != null) article.source!, timeAgo(article.publishedAt)].join(' · ');
    return Scaffold(
      appBar: AppBar(actions: [BookmarkButton(article: article), const SizedBox(width: 4)]),
      body: SingleChildScrollView(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 680),
            child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (article.imageUrl != null)
                    ClipRRect(
                      borderRadius: BorderRadius.circular(16),
                      child: AspectRatio(
                        aspectRatio: 16 / 9,
                        child: CachedNetworkImage(
                          imageUrl: article.imageUrl!,
                          fit: BoxFit.cover,
                          errorWidget: (_, _, _) => ColoredBox(color: scheme.surfaceContainerHighest),
                        ),
                      ),
                    ),
                  const SizedBox(height: 16),
                  Text(meta, style: theme.textTheme.labelLarge?.copyWith(color: scheme.onSurfaceVariant)),
                  const SizedBox(height: 8),
                  Text(article.title,
                      style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w700, height: 1.25)),
                  if (article.summary != null) ...[
                    const SizedBox(height: 12),
                    Text(article.summary!, style: theme.textTheme.bodyLarge?.copyWith(height: 1.5)),
                  ],
                  const SizedBox(height: 24),
                  FilledButton.icon(
                    onPressed: () => openInBrowser(Uri.parse(article.url)),
                    icon: const Icon(Icons.open_in_new),
                    label: Text('Read on ${article.source ?? 'the publisher\'s site'}'),
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
