import { error } from '@sveltejs/kit';
import { getPosts, getTags } from '#lib/server/posts';

export function entries() {
	return getTags().map(({ tag }) => ({ tag }));
}

export function load({ params }: { params: { tag: string } }) {
	const tag = decodeURIComponent(params.tag);
	const posts = getPosts().filter((post) => post.tags.includes(tag));
	if (!posts.length) error(404, '标签不存在');
	return { tag, posts };
}
