<script lang="ts">
	import type { Snippet } from 'svelte';
	import { cn } from '#lib/utils';

	let {
		open = $bindable(false),
		label = '',
		className = '',
		children
	}: {
		open?: boolean;
		label?: string;
		className?: string;
		children: Snippet;
	} = $props();

	let dialog: HTMLDialogElement | undefined = $state();

	$effect(() => {
		if (!dialog) return;
		if (open && !dialog.open) dialog.showModal();
		if (!open && dialog.open) dialog.close();
	});

	function handleClick(event: MouseEvent) {
		if (event.target === dialog) open = false;
	}
</script>

<dialog
	bind:this={dialog}
	aria-label={label}
	onclose={() => (open = false)}
	onclick={handleClick}
	class={cn(
		'm-0 h-dvh max-h-none w-full max-w-none border-0 bg-transparent p-0 backdrop:bg-black/40',
		className
	)}
>
	<div class="flex min-h-full items-start justify-center p-4 sm:p-6">
		<div class="w-full max-w-sm rounded-[12px] border border-border bg-background p-5 shadow-xl">
			{@render children()}
		</div>
	</div>
</dialog>
