import type { D1Database } from './d1';

export type PostSummary = {
	id: number;
	slug: string;
	title: string;
	summary: string;
	tags: string[];
	published: boolean;
	createdAt: string;
	updatedAt: string;
};

export type Post = PostSummary & { body: string };

export type Comment = {
	id: number;
	postSlug: string;
	name: string;
	body: string;
	status: 'pending' | 'approved';
	createdAt: string;
};

type PostRow = {
	id: number;
	slug: string;
	title: string;
	summary: string;
	body?: string;
	tags: string;
	published: number;
	created_at: string;
	updated_at: string;
};

type CommentRow = {
	id: number;
	post_slug: string;
	name: string;
	body: string;
	status: 'pending' | 'approved';
	created_at: string;
};

export type PostInput = {
	slug: string;
	title: string;
	summary: string;
	body: string;
	tags: string;
	published: boolean;
};

function splitTags(value: string): string[] {
	return value
		.split(',')
		.map((tag) => tag.trim())
		.filter(Boolean);
}

function mapPostSummary(row: PostRow): PostSummary {
	return {
		id: row.id,
		slug: row.slug,
		title: row.title,
		summary: row.summary,
		tags: splitTags(row.tags),
		published: Boolean(row.published),
		createdAt: row.created_at,
		updatedAt: row.updated_at
	};
}

export async function listPosts(
	db: D1Database,
	options: { includeDrafts?: boolean; limit?: number } = {}
): Promise<PostSummary[]> {
	const where = options.includeDrafts ? '' : 'WHERE published = 1';
	const limit = options.limit ? `LIMIT ${options.limit}` : '';

	const { results } = await db
		.prepare(
			`SELECT id, slug, title, summary, tags, published, created_at, updated_at
			 FROM posts ${where} ORDER BY created_at DESC ${limit}`
		)
		.all<PostRow>();

	return results.map(mapPostSummary);
}

export async function getPost(
	db: D1Database,
	slug: string,
	includeDrafts = false
): Promise<Post | undefined> {
	const row = await db
		.prepare(
			`SELECT id, slug, title, summary, body, tags, published, created_at, updated_at
			 FROM posts WHERE slug = ? ${includeDrafts ? '' : 'AND published = 1'}`
		)
		.bind(slug)
		.first<PostRow>();

	if (!row) return undefined;
	return { ...mapPostSummary(row), body: row.body ?? '' };
}

export async function createPost(db: D1Database, input: PostInput): Promise<void> {
	const now = new Date().toISOString();
	await db
		.prepare(
			`INSERT INTO posts (slug, title, summary, body, tags, published, created_at, updated_at)
			 VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
		)
		.bind(
			input.slug,
			input.title,
			input.summary,
			input.body,
			input.tags,
			input.published ? 1 : 0,
			now,
			now
		)
		.run();
}

export async function updatePost(db: D1Database, slug: string, input: PostInput): Promise<void> {
	const statements = [
		db
			.prepare(
				`UPDATE posts SET slug = ?, title = ?, summary = ?, body = ?, tags = ?, published = ?, updated_at = ?
				 WHERE slug = ?`
			)
			.bind(
				input.slug,
				input.title,
				input.summary,
				input.body,
				input.tags,
				input.published ? 1 : 0,
				new Date().toISOString(),
				slug
			)
	];

	if (input.slug !== slug) {
		statements.push(
			db.prepare('UPDATE comments SET post_slug = ? WHERE post_slug = ?').bind(input.slug, slug)
		);
	}

	await db.batch(statements);
}

export async function setPublished(db: D1Database, slug: string, published: boolean): Promise<void> {
	await db
		.prepare('UPDATE posts SET published = ?, updated_at = ? WHERE slug = ?')
		.bind(published ? 1 : 0, new Date().toISOString(), slug)
		.run();
}

export async function deletePost(db: D1Database, slug: string): Promise<void> {
	await db.batch([
		db.prepare('DELETE FROM comments WHERE post_slug = ?').bind(slug),
		db.prepare('DELETE FROM posts WHERE slug = ?').bind(slug)
	]);
}

export async function listComments(
	db: D1Database,
	options: { slug?: string; status?: 'pending' | 'approved'; limit?: number } = {}
): Promise<Comment[]> {
	const conditions: string[] = [];
	const params: unknown[] = [];

	if (options.slug) {
		conditions.push('post_slug = ?');
		params.push(options.slug);
	}
	if (options.status) {
		conditions.push('status = ?');
		params.push(options.status);
	}

	const where = conditions.length ? `WHERE ${conditions.join(' AND ')}` : '';
	const order = options.slug ? 'ASC' : 'DESC';
	const limit = `LIMIT ${options.limit ?? 50}`;

	const { results } = await db
		.prepare(
			`SELECT id, post_slug, name, body, status, created_at
			 FROM comments ${where} ORDER BY created_at ${order} ${limit}`
		)
		.bind(...params)
		.all<CommentRow>();

	return results.map((row) => ({
		id: row.id,
		postSlug: row.post_slug,
		name: row.name,
		body: row.body,
		status: row.status,
		createdAt: row.created_at
	}));
}

export async function addComment(
	db: D1Database,
	input: { slug: string; name: string; body: string; ipHash: string }
): Promise<void> {
	await db
		.prepare(
			`INSERT INTO comments (post_slug, name, body, status, created_at, ip_hash)
			 VALUES (?, ?, ?, 'pending', ?, ?)`
		)
		.bind(input.slug, input.name, input.body, new Date().toISOString(), input.ipHash)
		.run();
}

export async function recentComments(
	db: D1Database,
	ipHash: string,
	minutes = 10
): Promise<number> {
	const since = new Date(Date.now() - minutes * 60 * 1000).toISOString();
	const row = await db
		.prepare('SELECT COUNT(*) AS total FROM comments WHERE ip_hash = ? AND created_at > ?')
		.bind(ipHash, since)
		.first<{ total: number }>();
	return row?.total ?? 0;
}

export async function moderateComment(
	db: D1Database,
	id: number,
	status: 'pending' | 'approved'
): Promise<void> {
	await db.prepare('UPDATE comments SET status = ? WHERE id = ?').bind(status, id).run();
}

export async function deleteComment(db: D1Database, id: number): Promise<void> {
	await db.prepare('DELETE FROM comments WHERE id = ?').bind(id).run();
}

export async function recentLoginAttempts(
	db: D1Database,
	ipHash: string,
	minutes = 10
): Promise<number> {
	const since = new Date(Date.now() - minutes * 60 * 1000).toISOString();
	const row = await db
		.prepare('SELECT COUNT(*) AS total FROM login_attempts WHERE ip_hash = ? AND created_at > ?')
		.bind(ipHash, since)
		.first<{ total: number }>();
	return row?.total ?? 0;
}

export async function recordLoginAttempt(db: D1Database, ipHash: string): Promise<void> {
	await db
		.prepare('INSERT INTO login_attempts (ip_hash, created_at) VALUES (?, ?)')
		.bind(ipHash, new Date().toISOString())
		.run();
}

export async function clearLoginAttempts(db: D1Database, ipHash: string): Promise<void> {
	await db.prepare('DELETE FROM login_attempts WHERE ip_hash = ?').bind(ipHash).run();
}
