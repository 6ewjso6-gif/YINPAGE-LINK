<script lang="ts">
	import Badge from '#lib/components/ui/badge.svelte';
	import Button from '#lib/components/ui/button.svelte';
	import ToolCard from '#lib/components/tool-card.svelte';
	import { site, tools } from '#lib/site';
</script>

<svelte:head>
	<title>{site.title}</title>
	<meta name="description" content={site.description} />
</svelte:head>

<section class="pt-14 pb-12 sm:pt-20">
	<Badge tone="brand">工具优先 · 浏览器内处理</Badge>

	<h1 class="mt-5 max-w-2xl text-3xl leading-tight font-semibold tracking-tight text-balance sm:text-4xl">
		常用的工具，打开就能用
	</h1>

	<p class="mt-4 max-w-xl text-[15px] leading-relaxed text-muted-foreground">
		不用登录，也不上传文件。所有处理都发生在你这台设备上，完成之后直接下载。
	</p>

	<div class="mt-7 flex flex-wrap items-center gap-3">
		<Button href="/tools/image-convert">开始转换图片</Button>
		<Button href="/tools" variant="secondary">查看全部工具</Button>
	</div>
</section>

<section aria-labelledby="tools-heading" class="pb-4">
	<div class="flex items-baseline justify-between gap-4">
		<h2 id="tools-heading" class="text-base font-semibold tracking-tight">工具</h2>
		<a href="/tools" class="text-[13px] text-muted-foreground hover:text-foreground">全部 →</a>
	</div>

	<div class="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
		{#each tools as tool (tool.slug)}
			<ToolCard {tool} />
		{/each}
	</div>
</section>

<section aria-labelledby="principles-heading" class="pt-14">
	<h2 id="principles-heading" class="text-base font-semibold tracking-tight">这个站点怎么做的</h2>

	<div class="mt-4 grid gap-8 sm:grid-cols-3">
		<div>
			<p class="text-[13px] text-muted-foreground">01</p>
			<h3 class="mt-1 text-sm font-medium">本地优先</h3>
			<p class="mt-1.5 text-sm leading-relaxed text-muted-foreground">
				能放在浏览器里算的，就不经过服务器。
			</p>
		</div>
		<div>
			<p class="text-[13px] text-muted-foreground">02</p>
			<h3 class="mt-1 text-sm font-medium">一种语言</h3>
			<p class="mt-1.5 text-sm leading-relaxed text-muted-foreground">
				按钮、弹窗和状态共用一套设计 Token，不在页面里另起一套。
			</p>
		</div>
		<div>
			<p class="text-[13px] text-muted-foreground">03</p>
			<h3 class="mt-1 text-sm font-medium">每页完整</h3>
			<p class="mt-1.5 text-sm leading-relaxed text-muted-foreground">
				空、加载、失败、手机上溢出，都在发布前处理过。
			</p>
		</div>
	</div>
</section>
