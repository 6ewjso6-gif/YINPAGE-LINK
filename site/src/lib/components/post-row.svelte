<script lang="ts">
	import { formatDate, type PostMeta } from '#lib/content';

	let { post }: { post: PostMeta } = $props();
</script>

<a
	href={`/blog/${post.slug}`}
	class="group grid gap-1.5 border-b border-border py-5 transition-colors sm:grid-cols-[7rem_minmax(0,1fr)] sm:gap-6"
>
	<time datetime={post.date} class="text-xs text-muted-foreground tabular-nums sm:pt-0.5">
		{formatDate(post.date)}
	</time>

	<div class="min-w-0">
		<h3 class="text-[15px] font-medium tracking-tight group-hover:text-brand">{post.title}</h3>
		<p class="mt-1 text-sm leading-relaxed text-muted-foreground">{post.summary}</p>

		{#if post.tags.length}
			<div class="mt-2 flex flex-wrap gap-x-2 gap-y-1 text-xs text-muted-foreground">
				{#each post.tags as tag (tag)}
					<span>#{tag}</span>
				{/each}
			</div>
		{/if}
	</div>
</a>
