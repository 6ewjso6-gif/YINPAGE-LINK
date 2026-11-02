<script lang="ts">
	import type { Snippet } from 'svelte';
	import { cn } from '#lib/utils';

	let {
		className = '',
		children,
		...rest
	}: {
		className?: string;
		children: Snippet;
		[key: string]: unknown;
	} = $props();
</script>

<div class={cn('rounded-[12px] border border-border bg-surface', className)} {...rest}>
	{@render children()}
</div>
