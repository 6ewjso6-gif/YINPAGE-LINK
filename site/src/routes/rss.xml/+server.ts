import { getPosts } from '#lib/content';
import { site } from '#lib/site';

export const prerender = true;

function escapeXml(value: string): string {
	return value
		.replace(/&/g, '&amp;')
		.replace(/</g, '&lt;')
		.replace(/>/g, '&gt;');
}

export function GET() {
	const items = getPosts()
		.map((post) => {
			const link = `${site.url}/blog/${post.slug}`;
			const pubDate = new Date(`${post.date}T00:00:00+08:00`).toUTCString();

			return `		<item>
			<title>${escapeXml(post.title)}</title>
			<link>${link}</link>
			<guid isPermaLink="true">${link}</guid>
			<pubDate>${pubDate}</pubDate>
			<description>${escapeXml(post.summary)}</description>
		</item>`;
		})
		.join('\n');

	const body = `<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0">
	<channel>
		<title>${escapeXml(site.title)}</title>
		<link>${site.url}</link>
		<description>${escapeXml(site.description)}</description>
		<language>zh-CN</language>
${items}
	</channel>
</rss>
`;

	return new Response(body, {
		headers: { 'Content-Type': 'application/rss+xml; charset=utf-8' }
	});
}
