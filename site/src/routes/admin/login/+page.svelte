<script lang="ts">
	import type { PageProps } from './$types';
	import Badge from '#lib/components/ui/badge.svelte';
	import Button from '#lib/components/ui/button.svelte';
	import { site } from '#lib/site';

	let { form }: PageProps = $props();
</script>

<svelte:head>
	<title>登录 · {site.name}</title>
	<meta name="robots" content="noindex" />
</svelte:head>

<section class="mx-auto max-w-sm pt-20">
	<Badge tone="brand">后台</Badge>
	<h1 class="mt-4 text-xl font-semibold tracking-tight">写文章之前，先确认是你</h1>

	<form method="POST" class="mt-8 grid gap-3">
		{#if form?.error}
			<p class="text-[13px] text-danger">{form.error}</p>
		{/if}

		<input
			name="password"
			type="password"
			required
			aria-label="密码"
			placeholder="密码"
			class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm outline-none placeholder:text-muted-foreground focus:border-brand/60"
		/>

		<Button type="submit">登录</Button>
	</form>

	<p class="mt-6 text-xs leading-relaxed text-muted-foreground">
		这里只用于发文和审核评论。读者不需要账号。
	</p>
</section>
