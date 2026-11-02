import { site, tools } from '#lib/site';

export const prerender = true;

const staticRoutes = ['/', '/tools', '/about'];

function escapeXml(value: string): string {
	return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

export function GET() {
	const urls = [
		...staticRoutes,
		...tools.filter((tool) => tool.status === 'ready' && tool.href).map((tool) => tool.href as string)
	];

	const body = `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
${urls
	.map(
		(path) =>
			`	<url><loc>${escapeXml(`${site.url}${path === '/' ? '' : path}`)}</loc></url>`
	)
	.join('\n')}
</urlset>
`;

	return new Response(body, {
		headers: { 'Content-Type': 'application/xml; charset=utf-8' }
	});
}
