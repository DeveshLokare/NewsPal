-- Password reset with a security question (SPEC-v2 §7.3). Questions are data, like
-- categories: the app loads them from GET /api/auth/security-questions. Questions
-- whose answers are often public (mother's maiden name, birthplace, school) are
-- left out on purpose.
CREATE TABLE security_questions (
  id          SMALLSERIAL  PRIMARY KEY,
  text        VARCHAR(120) NOT NULL UNIQUE,
  sort_order  SMALLINT     NOT NULL DEFAULT 0
);

INSERT INTO security_questions (text, sort_order) VALUES
  ('What was the name of your first pet?', 1),
  ('What was the first concert or live show you went to?', 2),
  ('What is the name of the street you lived on as a child?', 3),
  ('What was the name of your childhood best friend?', 4),
  ('What was the make and model of your first car or bike?', 5),
  ('What is the title of the first book you loved?', 6),
  ('In what city or town did your parents meet?', 7),
  ('What was your favourite subject at school?', 8);

-- v1 users have no question until they set one in the app.
ALTER TABLE users
  ADD COLUMN security_question_id  SMALLINT REFERENCES security_questions(id),
  ADD COLUMN security_answer_hash  VARCHAR(100),           -- BCrypt of the normalised answer
  ADD COLUMN reset_failed_attempts SMALLINT NOT NULL DEFAULT 0,
  ADD COLUMN reset_locked_until    TIMESTAMPTZ,
  ADD COLUMN password_changed_at   TIMESTAMPTZ;            -- tokens issued before this are rejected
