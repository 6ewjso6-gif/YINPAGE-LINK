import { redirect } from '@sveltejs/kit';
import type { Handle } from '@sveltejs/kit/hooks';
import { verifySession } from '#lib/server/auth';
import { getEnv } from '#lib/server/d1';

export const handle: Handle = async ({ event, resolve }) => {
	const secret = getEnv(event.platform).SESSION_SECRET;
	event.locals.admin = await verifySession(secret, event.cookies.get('session'));

	const path = event.url.pathname;
	if (path.startsWith('/admin') && path !== '/admin/login' && !event.locals.admin) {
		redirect(303, '/admin/login');
	}

	return resolve(event);
};
