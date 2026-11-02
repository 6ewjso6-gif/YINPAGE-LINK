<script lang="ts">
	import type { PageProps } from './$types';
	import Button from '#lib/components/ui/button.svelte';
	import { formatDate } from '#lib/date';
	import { site } from '#lib/site';

	let { data, form }: PageProps = $props();
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
		<time datetime={data.post.createdAt} class="tabular-nums">
			{formatDate(data.post.createdAt)}
		</time>
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

	<section id="comments" class="mt-14 border-t border-border pt-8">
		<h2 class="text-base font-semibold tracking-tight">
			评论{#if data.comments.length}<span class="ml-1.5 text-muted-foreground">{data.comments.length}</span>{/if}
		</h2>

		{#if data.sent}
			<p
				class="mt-3 rounded-lg border border-border bg-surface-muted px-3 py-2 text-[13px] text-muted-foreground"
			>
				评论已提交，审核通过后会显示在这里。
			</p>
		{/if}

		{#if data.comments.length}
			<ul class="mt-5 grid gap-5">
				{#each data.comments as comment (comment.id)}
					<li class="border-l-2 border-border pl-4">
						<div class="flex flex-wrap items-baseline gap-x-2 text-xs text-muted-foreground">
							<span class="text-[13px] font-medium text-foreground">{comment.name}</span>
							<time datetime={comment.createdAt}>{formatDate(comment.createdAt)}</time>
						</div>
						<p class="mt-1.5 text-sm leading-relaxed whitespace-pre-line">{comment.body}</p>
					</li>
				{/each}
			</ul>
		{:else}
			<p class="mt-4 text-sm text-muted-foreground">还没有评论。</p>
		{/if}

		<form method="POST" action="?/comment" class="mt-8 grid gap-3">
			{#if form?.commentError}
				<p class="text-[13px] text-danger">{form.commentError}</p>
			{/if}

			<input
				name="name"
				type="text"
				value={form?.name ?? ''}
				required
				maxlength={20}
				aria-label="昵称"
				placeholder="昵称"
				class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm outline-none placeholder:text-muted-foreground focus:border-brand/60"
			/>
			<textarea
				name="body"
				rows={4}
				required
				maxlength={2000}
				aria-label="评论内容"
				placeholder="说点什么…"
				class="w-full resize-y rounded-lg border border-border bg-surface px-3 py-2 text-sm leading-relaxed outline-none placeholder:text-muted-foreground focus:border-brand/60"
			>{form?.body ?? ''}</textarea>
			<input name="website" class="hidden" tabindex={-1} autocomplete="off" aria-hidden="true" />

			<div class="flex flex-wrap items-center justify-between gap-3">
				<p class="text-xs text-muted-foreground">提交后需要审核，不会立刻显示。</p>
				<Button type="submit" size="sm">提交评论</Button>
			</div>
		</form>
	</section>

	<div class="mt-10 border-t border-border pt-6">
		<a
			href="/blog"
			class="text-[13px] text-muted-foreground transition-colors hover:text-foreground"
		>
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
