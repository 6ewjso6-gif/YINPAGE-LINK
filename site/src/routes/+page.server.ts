import { getPosts } from '#lib/server/posts';

export function load() {
	return { posts: getPosts().slice(0, 3) };
}
