<script lang="ts">
	import Badge from '#lib/components/ui/badge.svelte';
	import Card from '#lib/components/ui/card.svelte';
	import type { Tool } from '#lib/site';

	let { tool }: { tool: Tool } = $props();
</script>

<Card className="h-full transition-colors hover:border-brand/45">
	<div class="flex h-full flex-col p-5">
		<div class="flex items-start justify-between gap-3">
			<h3 class="text-[15px] font-semibold tracking-tight">{tool.name}</h3>
			{#if tool.status === 'ready'}
				<Badge tone="brand">可用</Badge>
			{:else}
				<Badge>筹备中</Badge>
			{/if}
		</div>

		<p class="mt-2 text-sm leading-relaxed text-muted-foreground">{tool.summary}</p>

		<div class="mt-auto flex items-center justify-between gap-3 pt-6">
			<div class="flex flex-wrap gap-x-3 gap-y-1">
				{#each tool.tags as tag (tag)}
					<span class="text-xs text-muted-foreground">{tag}</span>
				{/each}
			</div>

			{#if tool.status === 'ready' && tool.href}
				<a
					href={tool.href}
					class="group inline-flex items-center gap-1 text-[13px] font-medium text-brand"
				>
					打开
					<svg
						viewBox="0 0 24 24"
						class="h-3.5 w-3.5 transition-transform group-hover:translate-x-0.5"
						fill="none"
						stroke="currentColor"
						stroke-width="1.8"
						aria-hidden="true"
					>
						<path d="M5 12h14M13 6l6 6-6 6" />
					</svg>
				</a>
			{:else}
				<span class="text-[13px] text-muted-foreground">稍后推出</span>
			{/if}
		</div>
	</div>
</Card>
