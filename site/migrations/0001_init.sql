CREATE TABLE IF NOT EXISTS posts (
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	slug TEXT NOT NULL UNIQUE,
	title TEXT NOT NULL,
	summary TEXT NOT NULL DEFAULT '',
	body TEXT NOT NULL,
	tags TEXT NOT NULL DEFAULT '',
	published INTEGER NOT NULL DEFAULT 0,
	created_at TEXT NOT NULL,
	updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS comments (
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	post_slug TEXT NOT NULL,
	name TEXT NOT NULL,
	body TEXT NOT NULL,
	status TEXT NOT NULL DEFAULT 'pending',
	created_at TEXT NOT NULL,
	ip_hash TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS comments_post_idx ON comments (post_slug, status);
CREATE INDEX IF NOT EXISTS comments_ip_idx ON comments (ip_hash, created_at);

CREATE TABLE IF NOT EXISTS login_attempts (
	ip_hash TEXT NOT NULL,
	created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS login_attempts_idx ON login_attempts (ip_hash, created_at);
