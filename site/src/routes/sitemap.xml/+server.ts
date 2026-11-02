import { getEnv } from '#lib/server/d1';
import { listPosts } from '#lib/server/db';
import { site } from '#lib/site';

const staticRoutes = ['/', '/blog', '/about'];

function escapeXml(value: string): string {
	return value
		.replace(/&/g, '&amp;')
		.replace(/</g, '&lt;')
		.replace(/>/g, '&gt;');
}

export async function GET({ platform }) {
	const posts = await listPosts(getEnv(platform).DB);
	const urls = [...staticRoutes, ...posts.map((post) => `/blog/${post.slug}`)];

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
