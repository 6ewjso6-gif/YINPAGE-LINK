<script lang="ts">
	import { cn } from '#lib/utils';

	let {
		value = $bindable(''),
		options,
		className = '',
		...rest
	}: {
		value?: string;
		options: Array<{ value: string; label: string }>;
		className?: string;
		[key: string]: unknown;
	} = $props();
</script>

<div class="relative">
	<select
		bind:value
		class={cn(
			'h-10 w-full appearance-none rounded-lg border border-border bg-background px-3 pr-9 text-sm text-foreground focus:border-brand/60 focus:outline-none',
			className
		)}
		{...rest}
	>
		{#each options as option (option.value)}
			<option value={option.value}>{option.label}</option>
		{/each}
	</select>
	<svg
		class="pointer-events-none absolute top-1/2 right-3 h-4 w-4 -translate-y-1/2 text-muted-foreground"
		viewBox="0 0 24 24"
		fill="none"
		stroke="currentColor"
		stroke-width="1.6"
		aria-hidden="true"
	>
		<path d="m6 9 6 6 6-6" />
	</svg>
</div>
