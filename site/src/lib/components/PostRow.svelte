<script lang="ts">
	import type { PostMeta } from '#lib/types';
	import { formatDate } from '#lib/format';
	import Tag from './ui/Tag.svelte';

	let { post }: { post: PostMeta } = $props();
</script>

<article class="post-row">
	<div class="post-row__meta">
		<span>{formatDate(post.date)}</span>
		<span>{post.readingTime} 分钟</span>
	</div>
	<div class="post-row__body">
		<h3 class="post-row__title"><a href="/blog/{post.slug}/">{post.title}</a></h3>
		<p class="post-row__summary">{post.summary}</p>
		{#if post.tags.length}
			<div class="post-row__tags">
				{#each post.tags as tag (tag)}
					<Tag {tag} />
				{/each}
			</div>
		{/if}
	</div>
</article>
