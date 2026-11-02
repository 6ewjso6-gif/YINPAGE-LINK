import { SITE } from '#lib/site';
import { getPosts } from '#lib/server/posts';

export const prerender = true;

function escapeXml(value: string): string {
	return value
		.replace(/&/g, '&amp;')
		.replace(/</g, '&lt;')
		.replace(/>/g, '&gt;')
		.replace(/"/g, '&quot;');
}

export function GET() {
	const posts = getPosts();

	const items = posts
		.map((post) => {
			const url = `${SITE.url}/blog/${post.slug}/`;
			return `
		<item>
			<title>${escapeXml(post.title)}</title>
			<link>${url}</link>
			<guid isPermaLink="true">${url}</guid>
			<pubDate>${new Date(`${post.date}T00:00:00+08:00`).toUTCString()}</pubDate>
			<description>${escapeXml(post.summary)}</description>
			${post.tags.map((tag) => `<category>${escapeXml(tag)}</category>`).join('')}
		</item>`;
		})
		.join('');

	const xml = `<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom">
	<channel>
		<title>${escapeXml(SITE.name)} — ${escapeXml(SITE.tagline)}</title>
		<link>${SITE.url}</link>
		<description>${escapeXml(SITE.description)}</description>
		<language>zh-CN</language>
		<atom:link href="${SITE.url}/rss.xml" rel="self" type="application/rss+xml" />${items}
	</channel>
</rss>`;

	return new Response(xml, {
		headers: {
			'content-type': 'application/xml; charset=utf-8',
			'cache-control': 'public, max-age=3600'
		}
	});
}
