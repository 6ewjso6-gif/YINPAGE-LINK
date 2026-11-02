import { getPosts, getTags } from '#lib/server/posts';

export function load() {
	return { posts: getPosts(), tags: getTags() };
}
