import { marked } from 'marked';
import type { Post, PostMeta, TocItem } from '#lib/types';

const files = import.meta.glob('/src/content/posts/*.md', {
	query: '?raw',
	import: 'default',
	eager: true
}) as Record<string, string>;

function parseFrontmatter(raw: string): { data: Record<string, string>; body: string } {
	const match = raw.match(/^---\s*\n([\s\S]*?)\n---\s*\n?/);
	if (!match) return { data: {}, body: raw };

	const data: Record<string, string> = {};
	for (const line of match[1].split('\n')) {
		const index = line.indexOf(':');
		if (index === -1) continue;
		const key = line.slice(0, index).trim();
		let value = line.slice(index + 1).trim();
		if (
			(value.startsWith('"') && value.endsWith('"')) ||
			(value.startsWith("'") && value.endsWith("'"))
		) {
			value = value.slice(1, -1);
		}
		data[key] = value;
	}

	return { data, body: raw.slice(match[0].length) };
}

function parseTags(value = ''): string[] {
	return value
		.replace(/^\[|\]$/g, '')
		.split(',')
		.map((tag) => tag.trim().replace(/^["']|["']$/g, ''))
		.filter(Boolean);
}

function slugify(text: string): string {
	return text
		.toLowerCase()
		.replace(/[`*_]/g, '')
		.replace(/[^\w\u4e00-\u9fa5\s-]/g, '')
		.trim()
		.replace(/\s+/g, '-');
}

function addHeadingIds(html: string): { html: string; toc: TocItem[] } {
	const toc: TocItem[] = [];
	const used = new Map<string, number>();

	const withIds = html.replace(/<h([1-3])>([\s\S]*?)<\/h\1>/g, (_match, depthRaw, inner) => {
		const depth = Number(depthRaw);
		const text = inner.replace(/<[^>]+>/g, '').trim();
		let id = slugify(text) || `section-${toc.length + 1}`;
		const count = used.get(id) ?? 0;
		used.set(id, count + 1);
		if (count > 0) id = `${id}-${count + 1}`;
		toc.push({ depth, text, id });
		return `<h${depth} id="${id}">${inner}</h${depth}>`;
	});

	return { html: withIds, toc };
}

function readingTime(body: string): number {
	const chars = body.replace(/\s+/g, '').length;
	return Math.max(1, Math.round(chars / 350));
}

function buildPost(path: string, raw: string): Post {
	const slug = path.split('/').pop()!.replace(/\.md$/, '');
	const { data, body } = parseFrontmatter(raw);
	const rendered = marked.parse(body) as string;
	const { html, toc } = addHeadingIds(rendered);
	const summary = data.summary ?? body.replace(/[#>*`\n]/g, ' ').trim().slice(0, 90) + '…';

	return {
		slug,
		title: data.title ?? slug,
		date: data.date ?? '1970-01-01',
		summary,
		tags: parseTags(data.tags),
		readingTime: readingTime(body),
		draft: data.draft === 'true',
		html,
		toc
	};
}

const all: Post[] = Object.entries(files)
	.map(([path, raw]) => buildPost(path, raw))
	.sort((a, b) => b.date.localeCompare(a.date));

export function getPosts(): PostMeta[] {
	return all
		.filter((post) => !post.draft)
		.map(({ html: _html, toc: _toc, ...meta }) => meta);
}

export function getPost(slug: string): Post | undefined {
	return all.find((post) => post.slug === slug && !post.draft);
}

export function getTags(): { tag: string; count: number }[] {
	const counts = new Map<string, number>();
	for (const post of all.filter((item) => !item.draft)) {
		for (const tag of post.tags) counts.set(tag, (counts.get(tag) ?? 0) + 1);
	}
	return [...counts.entries()]
		.map(([tag, count]) => ({ tag, count }))
		.sort((a, b) => b.count - a.count || a.tag.localeCompare(b.tag));
}
