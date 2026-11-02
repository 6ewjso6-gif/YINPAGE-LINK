<script lang="ts">
	import type { PageData } from './$types';
	import { formatDate } from '#lib/format';
	import Tag from '#lib/components/ui/Tag.svelte';

	let { data }: { data: PageData } = $props();
</script>

<svelte:head>
	<title>{data.post.title} — 博客</title>
	<meta name="description" content={data.post.summary} />
	<meta property="og:type" content="article" />
	<meta property="og:title" content={data.post.title} />
	<meta property="og:description" content={data.post.summary} />
</svelte:head>

<div class="container">
	<div class="article">
		<article>
			<header class="article__head">
				<p class="eyebrow">{formatDate(data.post.date)}</p>
				<h1 class="article__title">{data.post.title}</h1>
				<p class="article__summary">{data.post.summary}</p>
				<div class="article__meta">
					<span>{data.post.readingTime} 分钟阅读</span>
					{#each data.post.tags as tag (tag)}
						<Tag {tag} />
					{/each}
				</div>
			</header>

			{#if data.post.toc.length}
				<details class="toc-mobile">
					<summary>本文目录</summary>
					<ul>
						{#each data.post.toc as item (item.id)}
							<li data-depth={item.depth}><a href="#{item.id}">{item.text}</a></li>
						{/each}
					</ul>
				</details>
			{/if}

			<div class="prose">
				{@html data.post.html}
			</div>

			<footer class="article__foot">
				{#if data.older}
					<a class="btn btn--ghost" href="/blog/{data.older.slug}/">← 更早：{data.older.title}</a>
				{:else}
					<a class="btn btn--ghost" href="/blog/">← 返回博客</a>
				{/if}
				{#if data.newer}
					<a class="btn btn--ghost" href="/blog/{data.newer.slug}/">更新：{data.newer.title} →</a>
				{/if}
			</footer>
		</article>

		{#if data.post.toc.length}
			<aside class="toc" aria-label="本文目录">
				<p class="toc__label">目录</p>
				<ul>
					{#each data.post.toc as item (item.id)}
						<li data-depth={item.depth}><a href="#{item.id}">{item.text}</a></li>
					{/each}
				</ul>
			</aside>
		{/if}
	</div>
</div>
