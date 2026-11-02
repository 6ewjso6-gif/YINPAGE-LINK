<script lang="ts">
	import Button from '#lib/components/ui/button.svelte';
	import { site } from '#lib/site';

	let { status = 404 }: { status?: number } = $props();
</script>

<svelte:head>
	<title>页面不存在 · {site.name}</title>
</svelte:head>

<div class="flex flex-col items-center gap-4 py-24 text-center">
	<p class="text-[13px] text-muted-foreground tabular-nums">{status}</p>
	<h1 class="text-2xl font-semibold tracking-tight">这个页面不在</h1>
	<p class="max-w-sm text-sm leading-relaxed text-muted-foreground">
		链接可能已经失效，或者地址打错了。回到首页，从工具列表重新开始。
	</p>
	<div class="mt-2 flex gap-3">
		<Button href="/">返回首页</Button>
		<Button href="/tools" variant="secondary">查看工具</Button>
	</div>
</div>
