import { redirect } from '@sveltejs/kit';
import {
	deleteComment,
	deletePost,
	listComments,
	listPosts,
	moderateComment,
	setPublished
} from '#lib/server/db';
import { getEnv } from '#lib/server/d1';
import { sessionCookie } from '#lib/server/auth';

export async function load({ platform }) {
	const db = getEnv(platform).DB;

	return {
		posts: await listPosts(db, { includeDrafts: true }),
		pending: await listComments(db, { status: 'pending', limit: 50 }),
		approved: await listComments(db, { status: 'approved', limit: 20 })
	};
}

export const actions = {
	logout: async ({ cookies }) => {
		cookies.delete(sessionCookie.name, { path: '/' });
		redirect(303, '/admin/login');
	},

	approve: async ({ request, platform }) => {
		const id = Number((await request.formData()).get('id'));
		if (id) await moderateComment(getEnv(platform).DB, id, 'approved');
	},

	hide: async ({ request, platform }) => {
		const id = Number((await request.formData()).get('id'));
		if (id) await moderateComment(getEnv(platform).DB, id, 'pending');
	},

	deleteComment: async ({ request, platform }) => {
		const id = Number((await request.formData()).get('id'));
		if (id) await deleteComment(getEnv(platform).DB, id);
	},

	toggle: async ({ request, platform }) => {
		const form = await request.formData();
		const slug = String(form.get('slug') ?? '');
		const next = form.get('next') === 'true';
		if (slug) await setPublished(getEnv(platform).DB, slug, next);
	},

	deletePost: async ({ request, platform }) => {
		const slug = String((await request.formData()).get('slug') ?? '');
		if (slug) await deletePost(getEnv(platform).DB, slug);
		redirect(303, '/admin');
	}
};
