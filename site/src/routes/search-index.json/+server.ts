import { getPosts, getPost } from '#lib/server/posts';

export const prerender = true;

function toPlainText(html: string): string {
	return html
		.replace(/<script[\s\S]*?<\/script>/g, ' ')
		.replace(/<[^>]+>/g, ' ')
		.replace(/&nbsp;/g, ' ')
		.replace(/&amp;/g, '&')
		.replace(/&lt;/g, '<')
		.replace(/&gt;/g, '>')
		.replace(/&quot;/g, '"')
		.replace(/\s+/g, ' ')
		.trim();
}

export function GET() {
	const docs = getPosts().map((meta) => {
		const post = getPost(meta.slug);
		return {
			slug: meta.slug,
			title: meta.title,
			summary: meta.summary,
			content: post ? toPlainText(post.html) : ''
		};
	});

	return new Response(JSON.stringify(docs), {
		headers: { 'content-type': 'application/json; charset=utf-8' }
	});
}
