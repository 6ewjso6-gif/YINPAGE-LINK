import { env } from 'cloudflare:workers';
import { error } from '@sveltejs/kit';

export interface D1PreparedStatement {
	bind(...values: unknown[]): D1PreparedStatement;
	first<T = Record<string, unknown>>(): Promise<T | null>;
	all<T = Record<string, unknown>>(): Promise<{ results: T[] }>;
	run(): Promise<{ success: boolean; meta?: { changes?: number } }>;
}

export interface D1Database {
	prepare(query: string): D1PreparedStatement;
	batch(statements: D1PreparedStatement[]): Promise<unknown>;
}

export type Bindings = {
	DB: D1Database;
	ADMIN_PASSWORD: string;
	SESSION_SECRET: string;
};

export function getEnv(platform?: App.Platform): Bindings {
	const bindings = platform?.env?.DB ? platform.env : env;
	if (!bindings?.DB) error(500, '缺少 D1 数据库绑定。');
	return bindings;
}
