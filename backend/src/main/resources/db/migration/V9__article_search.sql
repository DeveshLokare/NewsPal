-- Full-text search over stored articles (SPEC-v2 §8.1). Title matches weigh more than
-- summary matches; the column is kept up to date by Postgres itself.
ALTER TABLE articles ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
  setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
  setweight(to_tsvector('english', coalesce(summary, '')), 'B')) STORED;

CREATE INDEX idx_articles_search ON articles USING GIN (search_vector);
