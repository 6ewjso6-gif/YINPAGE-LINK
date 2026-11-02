<script lang="ts">
	import type { PageData } from './$types';

	let { data }: { data: PageData } = $props();
</script>

<svelte:head>
	<title>标签 — 博客</title>
	<meta name="description" content="按标签浏览全部文章。" />
</svelte:head>

<div class="container narrow">
	<p class="eyebrow">标签</p>
	<h1 style="font: 600 var(--text-2xl) var(--font-serif)">按标签浏览</h1>
	<p class="muted" style="margin-top: var(--space-3)">共 {data.tags.length} 个标签。</p>

	<div style="display: flex; flex-wrap: wrap; gap: var(--space-3); margin-top: var(--space-8)">
		{#each data.tags as item (item.tag)}
			<a class="chip" href="/tags/{encodeURIComponent(item.tag)}/" style="height: 32px">
				{item.tag}<span style="margin-left: 6px; opacity: 0.6">{item.count}</span>
			</a>
		{/each}
	</div>
</div>
