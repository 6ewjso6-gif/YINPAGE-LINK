<script lang="ts">
	import type { PageProps } from './$types';
	import PostRow from '#lib/components/post-row.svelte';
	import Badge from '#lib/components/ui/badge.svelte';
	import { site } from '#lib/site';
	import { cn } from '#lib/utils';

	let { data }: PageProps = $props();

	let activeTag = $state('');
	let tags = $derived(Array.from(new Set(data.posts.flatMap((post) => post.tags))));
	let visible = $derived(
		activeTag ? data.posts.filter((post) => post.tags.includes(activeTag)) : data.posts
	);
</script>

<svelte:head>
	<title>博客 · {site.name}</title>
	<meta name="description" content="所有文章，按时间从新到旧排列。" />
</svelte:head>

<section class="pt-14 pb-8">
	<Badge>博客</Badge>
	<h1 class="mt-4 text-2xl font-semibold tracking-tight sm:text-3xl">所有文章</h1>
	<p class="mt-3 max-w-xl text-sm leading-relaxed text-muted-foreground">
		共 {data.posts.length} 篇。按时间从新到旧，没有分页。
	</p>
</section>

{#if tags.length}
	<div class="flex flex-wrap items-center gap-2 pb-2">
		<button
			type="button"
			onclick={() => (activeTag = '')}
			class={cn(
				'rounded-full border px-3 py-1 text-xs transition-colors',
				activeTag === ''
					? 'border-brand/40 bg-brand-soft text-brand'
					: 'border-border text-muted-foreground hover:text-foreground'
			)}
		>
			全部
		</button>
		{#each tags as tag (tag)}
			<button
				type="button"
				onclick={() => (activeTag = tag)}
				class={cn(
					'rounded-full border px-3 py-1 text-xs transition-colors',
					activeTag === tag
						? 'border-brand/40 bg-brand-soft text-brand'
						: 'border-border text-muted-foreground hover:text-foreground'
				)}
			>
				#{tag}
			</button>
		{/each}
	</div>
{/if}

{#if visible.length}
	<div class="border-t border-border">
		{#each visible as post (post.slug)}
			<PostRow {post} />
		{/each}
	</div>
{:else}
	<p class="border-t border-border py-16 text-center text-sm text-muted-foreground">
		这个标签下还没有文章。
	</p>
{/if}
