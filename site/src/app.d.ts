import type { D1Database } from './lib/server/d1';

declare global {
	namespace App {
		interface Locals {
			admin: boolean;
		}

		interface Platform {
			env: {
				DB: D1Database;
				ADMIN_PASSWORD: string;
				SESSION_SECRET: string;
			};
		}
	}
}

export {};
