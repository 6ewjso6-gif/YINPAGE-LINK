import { fail, redirect } from '@sveltejs/kit';
import { createPost, getPost } from '#lib/server/db';
import { getEnv } from '#lib/server/d1';
import { readPostForm, validatePost } from '#lib/server/post-form';

export const actions = {
	save: async ({ request, platform }) => {
		const values = readPostForm(await request.formData());
		const message = validatePost(values);
		if (message) return fail(400, { error: message, values });

		const db = getEnv(platform).DB;
		if (await getPost(db, values.slug, true)) {
			return fail(400, { error: '这个网址已经被别的文章占用了。', values });
		}

		await createPost(db, values);
		redirect(303, '/admin');
	}
};
