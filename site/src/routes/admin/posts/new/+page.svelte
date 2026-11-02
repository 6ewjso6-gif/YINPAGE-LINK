<script lang="ts">
	import type { PageProps } from './$types';
	import PostEditor from '#lib/components/admin/post-editor.svelte';
	import { site } from '#lib/site';

	let { form }: PageProps = $props();
</script>

<svelte:head>
	<title>新建文章 · {site.name}</title>
	<meta name="robots" content="noindex" />
</svelte:head>

<section class="mx-auto max-w-3xl pt-12 pb-10">
	<a href="/admin" class="text-[13px] text-muted-foreground transition-colors hover:text-foreground">
		← 返回后台
	</a>
	<h1 class="mt-3 text-2xl font-semibold tracking-tight">新建文章</h1>

	<div class="mt-8">
		<PostEditor values={form?.values ?? null} error={form?.error ?? ''} />
	</div>
</section>
