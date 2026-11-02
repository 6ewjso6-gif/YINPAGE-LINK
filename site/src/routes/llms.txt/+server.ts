import { site, tools } from '#lib/site';

export const prerender = true;

export function GET() {
	const lines = [
		`# ${site.name}`,
		'',
		`> ${site.description}`,
		'',
		'## 工具',
		...tools.map((tool) => {
			if (tool.status === 'ready' && tool.href) {
				return `- [${tool.name}](${site.url}${tool.href}): ${tool.description}`;
			}
			return `- ${tool.name}（筹备中）: ${tool.description}`;
		}),
		'',
		'## 页面',
		...site.nav.map((item) => `- [${item.label}](${site.url}${item.href === '/' ? '' : item.href})`)
	];

	return new Response(`${lines.join('\n')}\n`, {
		headers: { 'Content-Type': 'text/plain; charset=utf-8' }
	});
}
