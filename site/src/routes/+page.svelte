<script lang="ts">
	import type { PageData } from './$types';
	import { SITE } from '#lib/site';
	import PostRow from '#lib/components/PostRow.svelte';
	import Button from '#lib/components/ui/Button.svelte';

	let { data }: { data: PageData } = $props();
</script>

<svelte:head>
	<title>{SITE.name} — {SITE.tagline}</title>
	<meta name="description" content={SITE.description} />
</svelte:head>

<div class="container">
	<section class="hero">
		<p class="eyebrow">个人博客 · 写于第 20 次之后</p>
		<h1>把网站做成一个<em>系统</em>，而不是一堆页面。</h1>
		<p class="hero__lead">
			这里记录前端、工具和建造过程。内容在构建期就成为 HTML，JavaScript 只在真正需要交互的时候出现。
		</p>
		<div class="hero__actions">
			<Button href="/blog/" variant="primary">读博客</Button>
			<Button href="/tools/">看看工具</Button>
		</div>
		<p class="hero__note">静态预渲染 · 无追踪 · 支持深色模式</p>
	</section>

	<section class="section">
		<div class="section-head">
			<h2>最近写的东西</h2>
			<a href="/blog/">全部文章 →</a>
		</div>
		{#each data.posts as post (post.slug)}
			<PostRow {post} />
		{/each}
	</section>

	<section class="section">
		<div class="section-head">
			<h2>工具</h2>
			<a href="/tools/">全部工具 →</a>
		</div>
		<div class="tool-grid">
			<a class="tool-card" href="/tools/image-convert/">
				<span class="tool-card__icon" aria-hidden="true">
					<svg width="18" height="18" viewBox="0 0 24 24" fill="none">
						<rect x="3" y="4" width="18" height="16" rx="2" stroke="currentColor" stroke-width="1.6" />
						<circle cx="8.5" cy="9.5" r="1.4" fill="currentColor" />
						<path d="m4 18 5-5 4 4 3-3 4 4" stroke="currentColor" stroke-width="1.6" />
					</svg>
				</span>
				<h3>图片格式转换</h3>
				<p>在浏览器本地把 PNG、JPEG、WebP 互转，可调质量与最大宽度，不上传任何文件。</p>
				<span class="tool-card__foot">可用 · 纯前端</span>
			</a>
			<article class="tool-card" data-status="planned">
				<span class="tool-card__icon" aria-hidden="true">
					<svg width="18" height="18" viewBox="0 0 24 24" fill="none">
						<path d="M4 6h16M4 12h10M4 18h13" stroke="currentColor" stroke-width="1.6" />
					</svg>
				</span>
				<h3>下一篇要做什么</h3>
				<p>先让一个工具从上传到下载完整跑通，再复制它的结构。这里会放下一个。</p>
				<span class="tool-card__foot">规划中</span>
			</article>
		</div>
	</section>
</div>
