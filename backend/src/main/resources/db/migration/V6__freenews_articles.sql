-- Free News API ids, so ingestion can skip /details calls for articles it already has
-- (SPEC-v2 §4.7). Leftover webz.io rows have none and expire with normal retention.
ALTER TABLE articles ADD COLUMN provider_id VARCHAR(64);
CREATE UNIQUE INDEX uq_articles_provider_id ON articles (provider_id) WHERE provider_id IS NOT NULL;

-- Free News API's quota is daily, so usage is counted per UTC day: '2026-10-24'.
-- Old monthly rows ('2026-10') simply stop matching.
ALTER TABLE api_usage RENAME COLUMN month TO period;
ALTER TABLE api_usage ALTER COLUMN period TYPE VARCHAR(10);
