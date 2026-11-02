import { marked } from 'marked';

marked.setOptions({ gfm: true, breaks: false });

export type PostMeta = {
	slug: string;
	title: string;
	date: string;
	summary: string;
	tags: string[];
};

export type Post = PostMeta & { html: string };

type Frontmatter = Record<string, string | string[]>;

const files = import.meta.glob('/src/content/blog/*.md', {
	eager: true,
	query: '?raw',
	import: 'default'
}) as Record<string, string>;

function unquote(value: string): string {
	return value.replace(/^["']|["']$/g, '');
}

function parseFrontmatter(raw: string): { data: Frontmatter; body: string } {
	const match = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?/.exec(raw);
	if (!match) return { data: {}, body: raw };

	const data: Frontmatter = {};
	for (const line of match[1].split(/\r?\n/)) {
		const entry = /^([A-Za-z0-9_-]+):\s*(.*)$/.exec(line.trim());
		if (!entry) continue;

		const value = entry[2].trim();
		if (value.startsWith('[') && value.endsWith(']')) {
			data[entry[1]] = value
				.slice(1, -1)
				.split(',')
				.map((item) => unquote(item.trim()))
				.filter(Boolean);
		} else {
			data[entry[1]] = unquote(value);
		}
	}

	return { data, body: raw.slice(match[0].length) };
}

function asString(value: string | string[] | undefined, fallback = ''): string {
	if (typeof value === 'string') return value;
	return fallback;
}

function buildPost(path: string, raw: string): Post {
	const slug = path.split('/').pop()?.replace(/\.md$/, '') ?? '';
	const { data, body } = parseFrontmatter(raw);

	return {
		slug,
		title: asString(data.title, slug),
		date: asString(data.date),
		summary: asString(data.summary),
		tags: Array.isArray(data.tags) ? data.tags : [],
		html: marked.parse(body) as string
	};
}

export function getPosts(): PostMeta[] {
	return Object.entries(files)
		.map(([path, raw]) => {
			const { slug, title, date, summary, tags } = buildPost(path, raw);
			return { slug, title, date, summary, tags };
		})
		.sort((a, b) => b.date.localeCompare(a.date));
}

export function getPost(slug: string): Post | undefined {
	const entry = Object.entries(files).find(([path]) => path.endsWith(`/${slug}.md`));
	return entry ? buildPost(entry[0], entry[1]) : undefined;
}

export function formatDate(date: string): string {
	if (!date) return '';
	const [year, month, day] = date.split('-');
	return `${year} 年 ${Number(month)} 月 ${Number(day)} 日`;
}
