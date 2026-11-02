import { error, fail, redirect } from '@sveltejs/kit';
import { deletePost, getPost, updatePost } from '#lib/server/db';
import { getEnv } from '#lib/server/d1';
import { readPostForm, validatePost } from '#lib/server/post-form';

export async function load({ params, platform }) {
	const post = await getPost(getEnv(platform).DB, params.slug, true);
	if (!post) error(404, '文章不存在');
	return { post };
}

export const actions = {
	save: async ({ request, params, platform }) => {
		const values = readPostForm(await request.formData());
		const message = validatePost(values);
		if (message) return fail(400, { error: message, values });

		const db = getEnv(platform).DB;
		if (values.slug !== params.slug) {
			const existing = await getPost(db, values.slug, true);
			if (existing) return fail(400, { error: '这个网址已经被别的文章占用了。', values });
		}

		await updatePost(db, params.slug, values);
		redirect(303, '/admin');
	},

	delete: async ({ params, platform }) => {
		await deletePost(getEnv(platform).DB, params.slug);
		redirect(303, '/admin');
	}
};
