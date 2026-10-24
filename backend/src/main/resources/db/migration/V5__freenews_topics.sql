-- News now comes from Free News API (SPEC-v2 §4.3): provider_query holds its topic
-- name. All 8 exist in GET /v1/topics (checked 2026-10-24).
UPDATE categories SET provider_query = slug
WHERE slug IN ('technology', 'business', 'sports', 'science', 'health', 'entertainment', 'world', 'politics');
