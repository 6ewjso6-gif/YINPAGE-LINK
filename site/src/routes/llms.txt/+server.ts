import { getPosts } from '#lib/content';
import { site } from '#lib/site';

export const prerender = true;

export function GET() {
	const lines = [
		`# ${site.name}`,
		'',
		`> ${site.description}`,
		'',
		'## 文章',
		...getPosts().map(
			(post) => `- [${post.title}](${site.url}/blog/${post.slug}): ${post.summary}`
		),
		'',
		'## 页面',
		...site.nav.map((item) => `- [${item.label}](${site.url}${item.href === '/' ? '' : item.href})`)
	];

	return new Response(`${lines.join('\n')}\n`, {
		headers: { 'Content-Type': 'text/plain; charset=utf-8' }
	});
}
