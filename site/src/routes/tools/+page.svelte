<script lang="ts">
	import { onDestroy } from 'svelte';
	import Badge from '#lib/components/ui/badge.svelte';
	import Button from '#lib/components/ui/button.svelte';
	import SegmentedControl from '#lib/components/ui/segmented-control.svelte';
	import Slider from '#lib/components/ui/slider.svelte';
	import ToolCard from '#lib/components/tool-card.svelte';
	import { site, tools } from '#lib/site';
</script>

<svelte:head>
	<title>全部工具 · {site.name}</title>
	<meta name="description" content="浏览器内运行的轻量工具集：图片转换、压缩、封面生成。" />
</svelte:head>

<section class="pt-14 pb-10">
	<Badge>工具集</Badge>
	<h1 class="mt-4 text-2xl font-semibold tracking-tight sm:text-3xl">全部工具</h1>
	<p class="mt-3 max-w-xl text-sm leading-relaxed text-muted-foreground">
		目前只有一个，但结构已经按多个工具预留。新工具会先在这里标注为筹备中。
	</p>
</section>

<div class="grid gap-4 pb-10 sm:grid-cols-2 lg:grid-cols-3">
	{#each tools as tool (tool.slug)}
		<ToolCard {tool} />
	{/each}
</div>
