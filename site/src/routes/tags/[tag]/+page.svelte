<script lang="ts">
	import type { PageData } from './$types';
	import PostRow from '#lib/components/PostRow.svelte';

	let { data }: { data: PageData } = $props();
</script>

<svelte:head>
	<title>标签：{data.tag} — 博客</title>
	<meta name="description" content="标签 {data.tag} 下的全部文章。" />
</svelte:head>

<div class="container narrow">
	<p class="eyebrow">标签</p>
	<h1 style="font: 600 var(--text-2xl) var(--font-serif)">#{data.tag}</h1>
	<p class="muted" style="margin-top: var(--space-3)">共 {data.posts.length} 篇。</p>

	<div style="margin-top: var(--space-6)">
		{#each data.posts as post (post.slug)}
			<PostRow {post} />
		{/each}
	</div>

	<a class="btn btn--ghost" style="margin-top: var(--space-8)" href="/blog/">← 返回全部文章</a>
</div>
