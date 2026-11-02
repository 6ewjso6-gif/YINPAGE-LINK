<script lang="ts">
	import Badge from '#lib/components/ui/badge.svelte';
	import Card from '#lib/components/ui/card.svelte';
	import { site } from '#lib/site';
</script>

<svelte:head>
	<title>关于 · {site.name}</title>
	<meta name="description" content="关于这个博客：只写字，不上传数据，也不做需要登录的功能。" />
</svelte:head>

<section class="pt-14 pb-10">
	<Badge>关于</Badge>
	<h1 class="mt-4 text-2xl font-semibold tracking-tight sm:text-3xl">一个只写字的地方</h1>
	<p class="mt-3 max-w-xl text-sm leading-relaxed text-muted-foreground">
		没有账号，没有会员，也没有推荐算法。文章按时间排列，你读到哪算哪。
	</p>
</section>

<div class="grid gap-5 pb-10 sm:grid-cols-2">
	<Card className="p-5">
		<h2 class="text-sm font-semibold">会写什么</h2>
		<ul class="mt-3 grid gap-2 text-sm leading-relaxed text-muted-foreground">
			<li>做东西时踩到的坑，以及怎么绕过去。</li>
			<li>读到的一段代码或一篇文章，值得记下来的部分。</li>
			<li>偶尔的随笔，不保证有用。</li>
		</ul>
	</Card>

	<Card className="p-5">
		<h2 class="text-sm font-semibold">不会写什么</h2>
		<ul class="mt-3 grid gap-2 text-sm leading-relaxed text-muted-foreground">
			<li>不追热点，不做资讯搬运。</li>
			<li>不为更新频率凑字数。</li>
			<li>不把错误留在原文里假装没发生。</li>
		</ul>
	</Card>
</div>

<section class="border-t border-border pt-8">
	<h2 class="text-sm font-semibold">怎么做的</h2>
	<p class="mt-2 max-w-2xl text-sm leading-relaxed text-muted-foreground">
		SvelteKit 负责路由与静态预渲染，文章在构建期从 Markdown 解析成 HTML。交互按需加载，正文不依赖脚本。页面、RSS、Sitemap 和 LLM 文本共用同一份内容源。
	</p>
	<p class="mt-4 text-sm text-muted-foreground">
		想改内容，往
		<span class="font-mono text-[13px] text-foreground">src/content/blog</span>
		里加一个 Markdown 文件即可。
	</p>
</section>
