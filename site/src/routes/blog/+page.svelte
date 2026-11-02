<script lang="ts">
	import type { PageData } from './$types';
	import PostRow from '#lib/components/PostRow.svelte';

	type SearchDoc = { slug: string; title: string; summary: string; content: string };

	let { data }: { data: PageData } = $props();

	let query = $state('');
	let activeTag = $state('');
	let index = $state<SearchDoc[] | null>(null);
	let indexLoading = $state(false);
	let indexFailed = $state(false);

	async function loadIndex() {
		if (index || indexLoading) return;
		indexLoading = true;
		try {
			const response = await fetch('/search-index.json');
			if (!response.ok) throw new Error(`HTTP ${response.status}`);
			index = (await response.json()) as SearchDoc[];
		} catch {
			indexFailed = true;
		} finally {
			indexLoading = false;
		}
	}

	const filtered = $derived(
		data.posts.filter((post) => {
			const words = query.trim().toLowerCase().split(/\s+/).filter(Boolean);
			if (activeTag && !post.tags.includes(activeTag)) return false;
			if (!words.length) return true;
			const doc = index?.find((item) => item.slug === post.slug);
			const haystack =
				`${post.title} ${post.summary} ${post.tags.join(' ')} ${doc?.content ?? ''}`.toLowerCase();
			return words.every((word) => haystack.includes(word));
		})
	);
</script>

<svelte:head>
	<title>博客 — 全部文章</title>
	<meta name="description" content="按时间排列的全部文章，关于前端、工具与建造过程。" />
</svelte:head>

<div class="container narrow">
	<p class="eyebrow">博客</p>
	<h1 style="font: 600 var(--text-2xl) var(--font-serif)">全部文章</h1>
	<p class="muted" style="margin-top: var(--space-3)">
		共 {data.posts.length} 篇。搜索会按需加载正文索引，首屏只带摘要。
	</p>

	<div class="blog-toolbar">
		<label class="search-field">
			<svg width="15" height="15" viewBox="0 0 24 24" fill="none" aria-hidden="true">
				<circle cx="11" cy="11" r="6.5" stroke="currentColor" stroke-width="1.7" />
				<path d="m16 16 4 4" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
			</svg>
			<input
				type="search"
				placeholder="搜索标题、标签或正文"
				bind:value={query}
				oninput={loadIndex}
				aria-label="搜索文章"
			/>
		</label>
		<div class="tag-filter">
			<button
				class="chip"
				type="button"
				data-active={activeTag === ''}
				onclick={() => (activeTag = '')}
			>
				全部
			</button>
			{#each data.tags as item (item.tag)}
				<button
					class="chip"
					type="button"
					data-active={activeTag === item.tag}
					onclick={() => (activeTag = activeTag === item.tag ? '' : item.tag)}
				>
					{item.tag}
				</button>
			{/each}
		</div>
	</div>

	{#if indexLoading}
		<p class="notice" data-tone="info">正在载入全文索引…</p>
	{:else if indexFailed}
		<p class="notice" data-tone="info">全文索引未载入，当前只搜索标题、摘要和标签。</p>
	{/if}

	<p class="muted" style="font-size: 12px">
		调试：query={query} · tag={activeTag} · 内联={data.posts.filter((post) =>
			post.title.includes(query)
		).length} · 结果={filtered.length}
	</p>

	<div>
		{#each filtered as post (post.slug)}
			<PostRow {post} />
		{/each}
	</div>

	{#if filtered.length === 0}
		<div class="empty" style="margin-top: var(--space-8)">
			<h3>没有匹配的文章</h3>
			<p>换个关键词，或者清除当前标签。</p>
			<button
				class="btn btn--ghost"
				type="button"
				onclick={() => {
					query = '';
					activeTag = '';
				}}
			>
				重置筛选
			</button>
		</div>
	{/if}
</div>
