<script lang="ts">
	import Badge from '#lib/components/ui/badge.svelte';
	import Card from '#lib/components/ui/card.svelte';
	import { site } from '#lib/site';
</script>

<svelte:head>
	<title>关于 · {site.name}</title>
	<meta name="description" content="关于这个小站：工具优先、本地处理、统一的设计语言。" />
</svelte:head>

<section class="pt-14 pb-10">
	<Badge>关于</Badge>
	<h1 class="mt-4 text-2xl font-semibold tracking-tight sm:text-3xl">
		一个只放常用工具的小站
</h1>
	<p class="mt-3 max-w-xl text-sm leading-relaxed text-muted-foreground">
		没有账号，没有会员，也没有把文件传到别人的服务器上。能用浏览器算完的，就在这里算完。
	</p>
</section>

<div class="grid gap-5 pb-10 sm:grid-cols-2">
	<Card className="p-5">
		<h2 class="text-sm font-semibold">做什么</h2>
		<ul class="mt-3 grid gap-2 text-sm leading-relaxed text-muted-foreground">
			<li>图片格式转换、压缩这类一次性需求。</li>
			<li>每个工具都能离线使用，不依赖后端。</li>
			<li>旧工具下线时，保留归档入口。</li>
		</ul>
	</Card>

	<Card className="p-5">
		<h2 class="text-sm font-semibold">不做什么</h2>
		<ul class="mt-3 grid gap-2 text-sm leading-relaxed text-muted-foreground">
			<li>不做需要登录才能用的功能。</li>
			<li>不收集图片内容，也不做云端存储。</li>
			<li>不为了新功能牺牲页面速度和可读性。</li>
		</ul>
	</Card>
</div>

<section class="border-t border-border pt-8">
	<h2 class="text-sm font-semibold">怎么做的</h2>
	<p class="mt-2 max-w-2xl text-sm leading-relaxed text-muted-foreground">
		SvelteKit 负责路由与静态预渲染，页面在构建期就写成 HTML；交互按需接管。界面基于一套
		Design Token，按钮、弹窗、输入和状态只有一种写法。
	</p>
	<p class="mt-4 text-sm text-muted-foreground">反馈入口还在搭建，先在本地跑起来看看效果。</p>
</section>
