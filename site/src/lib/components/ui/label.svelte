<script lang="ts">
	import type { Snippet } from 'svelte';
	import { cn } from '#lib/utils';

	let {
		for: htmlFor = '',
		className = '',
		children
	}: {
		for?: string;
		className?: string;
		children: Snippet;
	} = $props();
</script>

<label for={htmlFor} class={cn('text-[13px] font-medium text-foreground', className)}>
	{@render children()}
</label>
