<script lang="ts">
	import { cn } from '#lib/utils';

	let {
		value = $bindable(''),
		options,
		className = ''
	}: {
		value?: string;
		options: Array<{ value: string; label: string }>;
		className?: string;
	} = $props();
</script>

<div
	role="radiogroup"
	class={cn('inline-flex rounded-lg border border-border bg-surface p-1', className)}
>
	{#each options as option (option.value)}
		<button
			type="button"
			role="radio"
			aria-checked={value === option.value}
			onclick={() => (value = option.value)}
			class={cn(
				'h-7 rounded-md px-3 text-[13px] font-medium transition-colors',
				value === option.value
					? 'bg-background text-foreground shadow-[0_1px_2px_rgba(0,0,0,0.08)]'
					: 'text-muted-foreground hover:text-foreground'
			)}
		>
			{option.label}
		</button>
	{/each}
</div>
