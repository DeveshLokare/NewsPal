-- Archived items carry the category slug (like feed items); the app maps slugs to
-- display names via GET /api/categories.
ALTER TABLE archived_articles RENAME COLUMN category_name TO category;
