import { getPosts } from '#lib/content';

export function load() {
	return { posts: getPosts() };
}
