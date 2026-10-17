CREATE TABLE users (
  id            BIGSERIAL PRIMARY KEY,
  email         VARCHAR(254) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE categories (
  id              SMALLSERIAL PRIMARY KEY,
  slug            VARCHAR(40)  NOT NULL UNIQUE,
  name            VARCHAR(60)  NOT NULL,
  provider_query  TEXT         NOT NULL,
  sort_order      SMALLINT     NOT NULL DEFAULT 0,
  last_fetched_at TIMESTAMPTZ
);

CREATE TABLE user_categories (
  user_id     BIGINT   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  category_id SMALLINT NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
  PRIMARY KEY (user_id, category_id)
);

CREATE TABLE articles (
  id           BIGSERIAL PRIMARY KEY,
  url_hash     CHAR(64)     NOT NULL UNIQUE,
  url          TEXT         NOT NULL,
  title        TEXT         NOT NULL,
  summary      VARCHAR(500),
  image_url    TEXT,
  source       VARCHAR(200),
  published_at TIMESTAMPTZ  NOT NULL,
  category_id  SMALLINT     NOT NULL REFERENCES categories(id),
  fetched_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_articles_cat_pub ON articles (category_id, published_at DESC, id DESC);
CREATE INDEX idx_articles_pub     ON articles (published_at DESC, id DESC);

CREATE TABLE archived_articles (
  id            BIGSERIAL PRIMARY KEY,
  user_id       BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  url_hash      CHAR(64)     NOT NULL,
  url           TEXT         NOT NULL,
  title         TEXT         NOT NULL,
  summary       VARCHAR(500),
  image_url     TEXT,
  source        VARCHAR(200),
  published_at  TIMESTAMPTZ,
  category_name VARCHAR(60),
  archived_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
  UNIQUE (user_id, url_hash)
);
CREATE INDEX idx_archive_user ON archived_articles (user_id, archived_at DESC);

CREATE TABLE api_usage (
  month   CHAR(7)     NOT NULL,          -- e.g. '2026-10'
  bucket  VARCHAR(20) NOT NULL,          -- 'ingestion' | 'older'
  calls   INT         NOT NULL DEFAULT 0,
  PRIMARY KEY (month, bucket)
);
