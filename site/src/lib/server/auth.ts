const COOKIE_NAME = 'session';
const MAX_AGE = 60 * 60 * 24 * 7;

function toBase64Url(bytes: Uint8Array): string {
	let binary = '';
	for (const byte of bytes) binary += String.fromCharCode(byte);
	return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

async function hmac(secret: string, message: string): Promise<string> {
	const key = await crypto.subtle.importKey(
		'raw',
		new TextEncoder().encode(secret),
		{ name: 'HMAC', hash: 'SHA-256' },
		false,
		['sign']
	);
	const signature = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(message));
	return toBase64Url(new Uint8Array(signature));
}

export async function sha256(value: string): Promise<string> {
	const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value));
	return toBase64Url(new Uint8Array(digest));
}

export async function createSession(secret: string): Promise<string> {
	const expires = Date.now() + MAX_AGE * 1000;
	return `${expires}.${await hmac(secret, String(expires))}`;
}

export async function verifySession(secret: string, value: string | undefined): Promise<boolean> {
	if (!secret || !value) return false;

	const [expires, signature] = value.split('.');
	if (!expires || !signature) return false;
	if (Number(expires) < Date.now()) return false;

	const expected = await hmac(secret, expires);
	if (expected.length !== signature.length) return false;

	let mismatch = 0;
	for (let i = 0; i < expected.length; i += 1) {
		mismatch |= expected.charCodeAt(i) ^ signature.charCodeAt(i);
	}
	return mismatch === 0;
}

export const sessionCookie = {
	name: COOKIE_NAME,
	options: {
		path: '/',
		httpOnly: true,
		sameSite: 'lax' as const,
		maxAge: MAX_AGE
	}
};
