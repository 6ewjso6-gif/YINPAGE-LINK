<script lang="ts">
	import type { PageProps } from './$types';
	import PostRow from '#lib/components/post-row.svelte';
	import Badge from '#lib/components/ui/badge.svelte';
	import Button from '#lib/components/ui/button.svelte';
	import { site } from '#lib/site';

	let { data }: PageProps = $props();
</script>

<svelte:head>
	<title>{site.title}</title>
	<meta name="description" content={site.description} />
</svelte:head>

<section class="pt-14 pb-12 sm:pt-20">
	<Badge tone="brand">记录与随笔</Badge>

	<h1 class="mt-5 max-w-2xl text-3xl leading-tight font-semibold tracking-tight text-balance sm:text-4xl">
		把想写的东西，写清楚
	</h1>

	<p class="mt-4 max-w-xl text-[15px] leading-relaxed text-muted-foreground">
		不用登录，也没有加载动画。文章在构建期就写成 HTML，打开链接，正文就在那里。
	</p>

	<div class="mt-7 flex flex-wrap items-center gap-3">
		{#if data.posts.length}
			<Button href={`/blog/${data.posts[0].slug}`}>读最新一篇</Button>
		{/if}
		<Button href="/blog" variant="secondary">全部文章</Button>
	</div>
</section>

<section aria-labelledby="latest-heading" class="pb-4">
	<div class="flex items-baseline justify-between gap-4">
		<h2 id="latest-heading" class="text-base font-semibold tracking-tight">最近写的</h2>
		<a href="/blog" class="text-[13px] text-muted-foreground transition-colors hover:text-foreground">
			全部 →
		</a>
	</div>

	{#if data.posts.length}
		<div class="mt-4 border-t border-border">
			{#each data.posts as post (post.slug)}
				<PostRow {post} />
			{/each}
		</div>
	{:else}
		<p class="mt-4 border-t border-border py-16 text-center text-sm text-muted-foreground">
			还没有文章。往 `src/content/blog` 里放一个 Markdown 文件就会出现在这里。
		</p>
	{/if}
</section>

<section aria-labelledby="about-heading" class="pt-14">
	<h2 id="about-heading" class="text-base font-semibold tracking-tight">这个站怎么做的</h2>

	<div class="mt-4 grid gap-8 sm:grid-cols-3">
		<div>
			<p class="text-[13px] text-muted-foreground">01</p>
			<h3 class="mt-1 text-sm font-medium">内容先行</h3>
			<p class="mt-1.5 text-sm leading-relaxed text-muted-foreground">
				Markdown 在构建时解析，标题、正文、代码块都已经是 HTML。
			</p>
		</div>
		<div>
			<p class="text-[13px] text-muted-foreground">02</p>
			<h3 class="mt-1 text-sm font-medium">一种语言</h3>
			<p class="mt-1.5 text-sm leading-relaxed text-muted-foreground">
				按钮、卡片和弹窗共用一套设计 Token，不在页面里另起一套。
			</p>
		</div>
		<div>
			<p class="text-[13px] text-muted-foreground">03</p>
			<h3 class="mt-1 text-sm font-medium">同一份源</h3>
			<p class="mt-1.5 text-sm leading-relaxed text-muted-foreground">
				页面、RSS、Sitemap 和 llms.txt 都从同一批 Markdown 生成。
			</p>
		</div>
	</div>
</section>
