import { getPosts } from '#lib/content';

export function load() {
	return { posts: getPosts().slice(0, 3) };
}
