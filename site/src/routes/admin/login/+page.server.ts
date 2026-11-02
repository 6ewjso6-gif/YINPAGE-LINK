import { fail, redirect } from '@sveltejs/kit';
import { createSession, sessionCookie, sha256 } from '#lib/server/auth';
import { clearLoginAttempts, recentLoginAttempts, recordLoginAttempt } from '#lib/server/db';
import { getEnv } from '#lib/server/d1';

export const actions = {
	default: async ({ request, cookies, platform, getClientAddress, url }) => {
		const env = getEnv(platform);
		const password = String((await request.formData()).get('password') ?? '');
		const ipHash = await sha256(`${env.SESSION_SECRET}:${getClientAddress()}`);

		if ((await recentLoginAttempts(env.DB, ipHash)) >= 6) {
			return fail(429, { error: '尝试次数过多，请十分钟后再试。' });
		}

		if (!env.ADMIN_PASSWORD || password !== env.ADMIN_PASSWORD) {
			await recordLoginAttempt(env.DB, ipHash);
			return fail(400, { error: '密码不对。' });
		}

		await clearLoginAttempts(env.DB, ipHash);
		cookies.set(sessionCookie.name, await createSession(env.SESSION_SECRET), {
			...sessionCookie.options,
			secure: url.protocol === 'https:'
		});

		redirect(303, '/admin');
	}
};
