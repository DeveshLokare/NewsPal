/// Second-level labels under which country TLDs register names, e.g.
/// bbc.co.uk or abc.net.au.
const _secondLevel = {'co', 'com', 'net', 'org', 'ac', 'gov', 'edu'};

/// The "site" a host belongs to: `www.bbc.co.uk` → `bbc.co.uk`,
/// `edition.cnn.com` → `cnn.com`. An approximation of the public-suffix rules,
/// good enough to tell "same publisher" from "somewhere else".
String registrableDomain(String host) {
  final labels = host.toLowerCase().split('.').where((l) => l.isNotEmpty).toList();
  if (labels.length <= 2) return labels.join('.');
  final tld = labels.last;
  final second = labels[labels.length - 2];
  final take = (tld.length == 2 && _secondLevel.contains(second)) ? 3 : 2;
  return labels.sublist(labels.length - take).join('.');
}

/// Whether a link tapped inside an article should leave the app (SPEC §9.3):
/// anything that isn't http(s), or that goes to a different site than the
/// article itself.
bool shouldOpenExternally(Uri article, Uri target) {
  if (target.scheme != 'http' && target.scheme != 'https') return true;
  if (target.host.isEmpty) return true;
  return registrableDomain(article.host) != registrableDomain(target.host);
}
