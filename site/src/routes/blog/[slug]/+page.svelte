<script lang="ts">
	import type { PageProps } from './$types';
	import { formatDate } from '#lib/content';
	import { site } from '#lib/site';

	let { data }: PageProps = $props();
</script>

<svelte:head>
	<title>{data.post.title} · {site.name}</title>
	<meta name="description" content={data.post.summary} />
	<link rel="canonical" href={`${site.url}/blog/${data.post.slug}`} />
	<meta property="og:type" content="article" />
	<meta property="og:title" content={data.post.title} />
	<meta property="og:description" content={data.post.summary} />
</svelte:head>

<article class="mx-auto max-w-2xl pt-10 pb-12">
	<a
		href="/blog"
		class="inline-flex items-center gap-1.5 text-[13px] text-muted-foreground transition-colors hover:text-foreground"
	>
		<svg
			viewBox="0 0 24 24"
			class="h-3.5 w-3.5"
			fill="none"
			stroke="currentColor"
			stroke-width="1.8"
			aria-hidden="true"
		>
			<path d="M19 12H5M11 18l-6-6 6-6" />
		</svg>
		所有文章
	</a>

	<h1 class="mt-5 text-2xl leading-snug font-semibold tracking-tight text-balance sm:text-3xl">
		{data.post.title}
	</h1>

	<div class="mt-4 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
		<time datetime={data.post.date} class="tabular-nums">{formatDate(data.post.date)}</time>
		{#if data.post.tags.length}
			<span class="text-border">|</span>
			{#each data.post.tags as tag (tag)}
				<span>#{tag}</span>
			{/each}
		{/if}
	</div>

	{#if data.post.summary}
		<p class="mt-6 border-l-2 border-border pl-4 text-sm leading-relaxed text-muted-foreground">
			{data.post.summary}
		</p>
	{/if}

	<div class="article mt-8 text-[15px]">
		{@html data.post.html}
	</div>

	<div class="mt-14 border-t border-border pt-6">
		<a href="/blog" class="text-[13px] text-muted-foreground transition-colors hover:text-foreground">
			← 返回所有文章
		</a>
	</div>
</article>

<style>
	.article :global(p) {
		margin-top: 1rem;
		line-height: 1.85;
	}

	.article :global(h2) {
		margin-top: 2.5rem;
		font-size: 1.125rem;
		font-weight: 600;
		letter-spacing: -0.01em;
	}

	.article :global(h3) {
		margin-top: 1.75rem;
		font-size: 1rem;
		font-weight: 600;
	}

	.article :global(ul),
	.article :global(ol) {
		margin-top: 1rem;
		padding-left: 1.25rem;
		line-height: 1.85;
	}

	.article :global(ul) {
		list-style: disc;
	}

	.article :global(ol) {
		list-style: decimal;
	}

	.article :global(li) {
		margin-top: 0.4rem;
	}

	.article :global(li)::marker {
		color: var(--muted-foreground);
	}

	.article :global(a) {
		color: var(--brand);
		text-decoration: underline;
		text-underline-offset: 3px;
	}

	.article :global(strong) {
		font-weight: 600;
	}

	.article :global(code) {
		padding: 0.15em 0.35em;
		border-radius: 4px;
		background: var(--surface-muted);
		font-family: var(--font-mono);
		font-size: 0.85em;
	}

	.article :global(pre) {
		margin-top: 1.25rem;
		padding: 1rem;
		overflow-x: auto;
		border: 1px solid var(--border);
		border-radius: 10px;
		background: var(--surface);
	}

	.article :global(pre code) {
		padding: 0;
		background: none;
		font-size: 13px;
		line-height: 1.7;
	}

	.article :global(blockquote) {
		margin-top: 1.25rem;
		padding-left: 1rem;
		border-left: 2px solid var(--brand);
		color: var(--muted-foreground);
	}

	.article :global(hr) {
		margin: 2.5rem 0;
		border: 0;
		border-top: 1px solid var(--border);
	}

	.article :global(img) {
		margin-top: 1.25rem;
		border-radius: 10px;
	}
</style>
