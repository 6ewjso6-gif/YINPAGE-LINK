<script lang="ts">
	import { cn } from '#lib/utils';

	let {
		value = $bindable(50),
		min = 0,
		max = 100,
		step = 1,
		label = '',
		display = '',
		className = '',
		...rest
	}: {
		value?: number;
		min?: number;
		max?: number;
		step?: number;
		label?: string;
		display?: string;
		className?: string;
		[key: string]: unknown;
	} = $props();
</script>

<label class={cn('grid gap-2', className)}>
	<span class="flex items-baseline justify-between gap-4 text-[13px]">
		<span class="font-medium text-foreground">{label}</span>
		<span class="tabular-nums text-muted-foreground">{display}</span>
	</span>
	<input
		bind:value
		type="range"
		{min}
		{max}
		{step}
		class="h-1.5 w-full cursor-pointer appearance-none rounded-full bg-surface-muted accent-[var(--brand)]"
		{...rest}
	/>
</label>
