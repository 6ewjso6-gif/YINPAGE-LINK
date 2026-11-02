<script lang="ts">
	import type { PageProps } from './$types';
	import PostEditor from '#lib/components/admin/post-editor.svelte';
	import Button from '#lib/components/ui/button.svelte';
	import { site } from '#lib/site';

	let { data, form }: PageProps = $props();
</script>

<svelte:head>
	<title>编辑 {data.post.title} · 后台</title>
	<meta name="robots" content="noindex" />
</svelte:head>

<section class="mx-auto max-w-3xl pt-12 pb-10">
	<a href="/admin" class="text-[13px] text-muted-foreground transition-colors hover:text-foreground">
		← 返回后台
	</a>

	<div class="mt-3 flex flex-wrap items-baseline justify-between gap-3">
		<h1 class="text-2xl font-semibold tracking-tight">编辑文章</h1>
		<a
			href={`/blog/${data.post.slug}`}
			class="text-[13px] text-muted-foreground underline-offset-4 transition-colors hover:text-foreground hover:underline"
		>
			查看页面 ↗
		</a>
	</div>

	<div class="mt-8">
		<PostEditor post={data.post} values={form?.values ?? null} error={form?.error ?? ''} />
	</div>

	<form
		method="POST"
		action="?/delete"
		class="mt-8 border-t border-border pt-6"
		onsubmit={(event) => {
			if (!confirm('删除文章会连同评论一起删除，确定？')) event.preventDefault();
		}}
	>
		<Button variant="danger" size="sm" type="submit">删除这篇文章</Button>
	</form>
</section>
