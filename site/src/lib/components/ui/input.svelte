<script lang="ts">
	import { cn } from '#lib/utils';

	let {
		value = $bindable(''),
		type = 'text',
		className = '',
		...rest
	}: {
		value?: string;
		type?: string;
		className?: string;
		[key: string]: unknown;
	} = $props();
</script>

<input
	bind:value
	{type}
	class={cn(
		'h-10 w-full rounded-lg border border-border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground/70 focus:border-brand/60 focus:outline-none',
		className
	)}
	{...rest}
/>
