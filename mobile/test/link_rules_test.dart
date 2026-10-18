import 'package:flutter_test/flutter_test.dart';
import 'package:newspal/core/link_rules.dart';

void main() {
  final article = Uri.parse('https://www.bbc.com/news/tech-123');

  bool external(String target) => shouldOpenExternally(article, Uri.parse(target));

  test('same site stays in the app, with or without www and subdomains', () {
    expect(external('https://www.bbc.com/news/other'), isFalse);
    expect(external('https://bbc.com/sport'), isFalse);
    expect(external('https://m.bbc.com/page'), isFalse);
    expect(external('http://www.bbc.com/page'), isFalse);
  });

  test('other sites open in the browser', () {
    expect(external('https://twitter.com/bbc'), isTrue);
    expect(external('https://www.bbc.co.uk/news'), isTrue, reason: 'different registrable domain');
  });

  test('non-web links open externally', () {
    expect(external('mailto:news@bbc.com'), isTrue);
    expect(external('tel:+441234567'), isTrue);
    expect(external('intent://open#Intent;end'), isTrue);
  });

  test('registrable domain handles country second-level domains', () {
    expect(registrableDomain('www.bbc.co.uk'), 'bbc.co.uk');
    expect(registrableDomain('news.abc.net.au'), 'abc.net.au');
    expect(registrableDomain('edition.cnn.com'), 'cnn.com');
    expect(registrableDomain('example.io'), 'example.io');
    expect(registrableDomain('WWW.Example.COM'), 'example.com');
  });
}
