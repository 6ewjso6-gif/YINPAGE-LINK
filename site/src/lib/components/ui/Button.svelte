<script lang="ts">
	import type { Snippet } from 'svelte';

	type Variant = 'default' | 'primary' | 'ghost' | 'danger';

	let {
		href = '',
		variant = 'default',
		type = 'button',
		disabled = false,
		onclick,
		children
	}: {
		href?: string;
		variant?: Variant;
		type?: 'button' | 'submit';
		disabled?: boolean;
		onclick?: (event: MouseEvent) => void;
		children: Snippet;
	} = $props();

	const className = $derived(['btn', variant !== 'default' && `btn--${variant}`].filter(Boolean).join(' '));
</script>

{#if href}
	<a class={className} {href}>
		{@render children()}
	</a>
{:else}
	<button class={className} {type} {disabled} {onclick}>
		{@render children()}
	</button>
{/if}
