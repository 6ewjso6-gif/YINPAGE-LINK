import { error, fail, redirect } from '@sveltejs/kit';
import { marked } from 'marked';
import { sha256 } from '#lib/server/auth';
import { addComment, getPost, listComments, recentComments } from '#lib/server/db';
import { getEnv } from '#lib/server/d1';

export async function load({ params, platform, url }) {
	const env = getEnv(platform);
	const post = await getPost(env.DB, params.slug);
	if (!post) error(404, '文章不存在');

	return {
		post: { ...post, html: marked.parse(post.body) as string },
		comments: await listComments(env.DB, { slug: post.slug, status: 'approved', limit: 100 }),
		sent: url.searchParams.get('sent') === '1'
	};
}

export const actions = {
	comment: async ({ request, params, platform, getClientAddress }) => {
		const env = getEnv(platform);
		const form = await request.formData();

		const name = String(form.get('name') ?? '').trim();
		const body = String(form.get('body') ?? '').trim();
		const honeypot = String(form.get('website') ?? '').trim();

		if (honeypot) redirect(303, `/blog/${params.slug}?sent=1#comments`);
		if (name.length < 1 || name.length > 20) {
			return fail(400, { commentError: '昵称需要在 1–20 个字符之间。', name, body });
		}
		if (body.length < 2 || body.length > 2000) {
			return fail(400, { commentError: '评论需要在 2–2000 个字符之间。', name, body });
		}

		const ipHash = await sha256(`${env.SESSION_SECRET}:${getClientAddress()}`);
		if ((await recentComments(env.DB, ipHash)) >= 3) {
			return fail(429, { commentError: '十分钟内最多发三条评论，稍后再试。', name, body });
		}

		await addComment(env.DB, { slug: params.slug, name, body, ipHash });
		redirect(303, `/blog/${params.slug}?sent=1#comments`);
	}
};
