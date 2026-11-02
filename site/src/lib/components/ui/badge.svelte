<script lang="ts">
	import type { Snippet } from 'svelte';
	import { cn } from '#lib/utils';

	type Tone = 'neutral' | 'brand' | 'success' | 'danger';

	let {
		tone = 'neutral',
		className = '',
		children
	}: {
		tone?: Tone;
		className?: string;
		children: Snippet;
	} = $props();

	const tones: Record<Tone, string> = {
		neutral: 'bg-surface-muted text-muted-foreground border-border',
		brand: 'bg-brand-soft text-brand border-brand/30',
		success: 'bg-success-soft text-success border-success/30',
		danger: 'bg-danger-soft text-danger border-danger/30'
	};
</script>

<span
	class={cn(
		'inline-flex h-6 items-center rounded-full border px-2 text-xs font-medium whitespace-nowrap',
		tones[tone],
		className
	)}
>
	{@render children()}
</span>
