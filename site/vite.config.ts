import adapter from '@sveltejs/adapter-cloudflare';
import { sveltekit } from '@sveltejs/kit/vite';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vite';

export default defineConfig({
	plugins: [
		tailwindcss(),
		sveltekit({
			compilerOptions: {
				runes: ({ filename }) =>
					filename.split(/[/\\]/).includes('node_modules') ? undefined : true
			},
			// 运行在 Cloudflare Workers：页面服务端渲染，D1 存文章与评论。
			adapter: adapter({
				platformProxy: { configPath: 'wrangler.jsonc' }
			})
		})
	]
});
