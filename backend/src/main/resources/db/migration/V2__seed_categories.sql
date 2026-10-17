-- Starting queries for webz.io; tune them against the webz.io query builder in M2 (SPEC §3.1).
INSERT INTO categories (slug, name, provider_query, sort_order) VALUES
  ('technology',    'Technology',    'category:"Science and Technology" language:english', 1),
  ('business',      'Business',      'category:"Economy, Business and Finance" language:english', 2),
  ('sports',        'Sports',        'category:Sports language:english', 3),
  ('science',       'Science',       '(category:"Science and Technology" AND (research OR study OR space)) language:english', 4),
  ('health',        'Health',        'category:Health language:english', 5),
  ('entertainment', 'Entertainment', 'category:"Arts, Culture and Entertainment" language:english', 6),
  ('world',         'World',         '(category:"Conflict, War and Peace" OR category:"Human Interest") language:english', 7),
  ('politics',      'Politics',      'category:Politics language:english', 8);
