interface CloudflareBindings {
	DB: import('./lib/server/d1').D1Database;
	ADMIN_PASSWORD: string;
	SESSION_SECRET: string;
}

declare module 'cloudflare:workers' {
	export const env: CloudflareBindings;
}
