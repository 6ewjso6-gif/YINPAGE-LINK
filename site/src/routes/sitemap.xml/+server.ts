import { SITE } from '#lib/site';
import { getPosts, getTags } from '#lib/server/posts';

export const prerender = true;

export function GET() {
	const staticRoutes = ['', 'blog', 'about', 'tools', 'tools/image-convert', 'tags'];

	const entries = [
		...staticRoutes.map((path) => ({
			loc: `${SITE.url}/${path}`.replace(/\/$/, '') + '/',
			lastmod: undefined as string | undefined
		})),
		...getTags().map(({ tag }) => ({
			loc: `${SITE.url}/tags/${encodeURIComponent(tag)}/`,
			lastmod: undefined as string | undefined
		})),
		...getPosts().map((post) => ({
			loc: `${SITE.url}/blog/${post.slug}/`,
			lastmod: post.date
		}))
	];

	const urls = entries
		.map(
			(entry) =>
				`\t<url><loc>${entry.loc.replace(/&/g, '&amp;')}</loc>${
					entry.lastmod ? `<lastmod>${entry.lastmod}</lastmod>` : ''
				}</url>`
		)
		.join('\n');

	const xml = `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
${urls}
</urlset>`;

	return new Response(xml, {
		headers: {
			'content-type': 'application/xml; charset=utf-8',
			'cache-control': 'public, max-age=3600'
		}
	});
}
