import { getEnv } from '#lib/server/d1';
import { listPosts } from '#lib/server/db';
import { site } from '#lib/site';

export async function GET({ platform }) {
	const posts = await listPosts(getEnv(platform).DB);

	const lines = [
		`# ${site.name}`,
		'',
		`> ${site.description}`,
		'',
		'## 文章',
		...posts.map((post) => `- [${post.title}](${site.url}/blog/${post.slug}): ${post.summary}`),
		'',
		'## 页面',
		...site.nav.map((item) => `- [${item.label}](${site.url}${item.href === '/' ? '' : item.href})`)
	];

	return new Response(`${lines.join('\n')}\n`, {
		headers: { 'Content-Type': 'text/plain; charset=utf-8' }
	});
}
