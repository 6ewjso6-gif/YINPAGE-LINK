import { error } from '@sveltejs/kit';
import { getPost, getPosts } from '#lib/server/posts';

export function entries() {
	return getPosts().map(({ slug }) => ({ slug }));
}

export function load({ params }: { params: { slug: string } }) {
	const post = getPost(params.slug);
	if (!post) error(404, '文章不存在');

	const posts = getPosts();
	const position = posts.findIndex((item) => item.slug === params.slug);

	return {
		post,
		newer: position > 0 ? posts[position - 1] : null,
		older: position >= 0 ? (posts[position + 1] ?? null) : null
	};
}
