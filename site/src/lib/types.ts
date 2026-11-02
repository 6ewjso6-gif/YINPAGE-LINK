export type TocItem = { depth: number; text: string; id: string };

export type PostMeta = {
	slug: string;
	title: string;
	date: string;
	summary: string;
	tags: string[];
	readingTime: number;
	draft: boolean;
};

export type Post = PostMeta & {
	html: string;
	toc: TocItem[];
};
