import { error } from '@sveltejs/kit';
import { getPost, getPosts } from '#lib/content';

export function entries() {
	return getPosts().map((post) => ({ slug: post.slug }));
}

export function load({ params }) {
	const post = getPost(params.slug);
	if (!post) error(404, '文章不存在');
	return { post };
}
