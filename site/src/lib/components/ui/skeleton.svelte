<script lang="ts">
	import { cn } from '#lib/utils';

	let { className = '' }: { className?: string } = $props();
</script>

<div class={cn('animate-pulse rounded-lg bg-surface-muted', className)}></div>
