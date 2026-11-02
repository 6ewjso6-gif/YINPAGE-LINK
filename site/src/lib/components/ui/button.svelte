<script lang="ts">
	import type { Snippet } from 'svelte';
	import { cn } from '#lib/utils';

	type Variant = 'primary' | 'secondary' | 'ghost' | 'danger';
	type Size = 'sm' | 'md';

	let {
		variant = 'primary',
		size = 'md',
		href = '',
		disabled = false,
		className = '',
		children,
		...rest
	}: {
		variant?: Variant;
		size?: Size;
		href?: string;
		disabled?: boolean;
		className?: string;
		children: Snippet;
		[key: string]: unknown;
	} = $props();

	const base =
		'inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-lg font-medium transition-colors disabled:pointer-events-none disabled:opacity-50';

	const sizes: Record<Size, string> = {
		sm: 'h-8 px-3 text-[13px]',
		md: 'h-10 px-4 text-sm'
	};

	const variants: Record<Variant, string> = {
		primary: 'bg-brand text-brand-foreground hover:bg-brand/90',
		secondary: 'border border-border bg-surface text-foreground hover:bg-surface-muted',
		ghost: 'text-foreground hover:bg-surface-muted',
		danger: 'bg-danger text-white hover:bg-danger/90'
	};

	let classes = $derived(cn(base, sizes[size], variants[variant], className));
</script>

{#if href}
	<a {href} class={classes} {...rest}>{@render children()}</a>
{:else}
	<button class={classes} {disabled} {...rest}>{@render children()}</button>
{/if}
