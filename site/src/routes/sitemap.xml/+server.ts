import { getPosts } from '#lib/content';
import { site } from '#lib/site';

export const prerender = true;

const staticRoutes = ['/', '/blog', '/about'];

function escapeXml(value: string): string {
	return value
		.replace(/&/g, '&amp;')
		.replace(/</g, '&lt;')
		.replace(/>/g, '&gt;');
}

export function GET() {
	const urls = [...staticRoutes, ...getPosts().map((post) => `/blog/${post.slug}`)];

	const body = `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
${urls
	.map((path) => `	<url><loc>${escapeXml(`${site.url}${path === '/' ? '' : path}`)}</loc></url>`)
	.join('\n')}
</urlset>
`;

	return new Response(body, {
		headers: { 'Content-Type': 'application/xml; charset=utf-8' }
	});
}
