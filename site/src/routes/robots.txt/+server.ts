import { SITE } from '#lib/site';

export const prerender = true;

export function GET() {
	const body = `User-agent: *
Allow: /

Sitemap: ${SITE.url}/sitemap.xml
`;

	return new Response(body, {
		headers: { 'content-type': 'text/plain; charset=utf-8' }
	});
}
