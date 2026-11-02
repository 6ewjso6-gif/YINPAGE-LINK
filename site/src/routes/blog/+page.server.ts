import { getEnv } from '#lib/server/d1';
import { listPosts } from '#lib/server/db';

export async function load({ platform }) {
	const db = getEnv(platform).DB;
	return { posts: await listPosts(db) };
}
