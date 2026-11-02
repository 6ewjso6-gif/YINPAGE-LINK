<script lang="ts">
	import { page } from '$app/state';
	import NotFound from '#lib/components/not-found.svelte';
</script>

<NotFound status={page.status} />
