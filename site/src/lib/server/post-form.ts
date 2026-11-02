export type PostFormValues = {
	slug: string;
	title: string;
	summary: string;
	body: string;
	tags: string;
	published: boolean;
};

export function readPostForm(form: FormData): PostFormValues {
	return {
		slug: String(form.get('slug') ?? '')
			.trim()
			.toLowerCase(),
		title: String(form.get('title') ?? '').trim(),
		summary: String(form.get('summary') ?? '').trim(),
		body: String(form.get('body') ?? '').trim(),
		tags: String(form.get('tags') ?? '')
			.split(',')
			.map((tag) => tag.trim())
			.filter(Boolean)
			.join(','),
		published: form.get('published') === 'on'
	};
}

export function validatePost(values: PostFormValues): string | null {
	if (!values.title) return '标题不能为空。';
	if (!/^[a-z0-9][a-z0-9-]{0,63}$/.test(values.slug)) {
		return '网址只能用小写字母、数字和连字符，且不能以连字符开头。';
	}
	if (!values.body) return '正文不能为空。';
	return null;
}
