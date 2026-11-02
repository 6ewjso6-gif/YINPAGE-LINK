<script lang="ts">
	import type { LayoutProps } from './$types';
	import '../app.css';
	import SiteFooter from '#lib/components/site-footer.svelte';
	import SiteHeader from '#lib/components/site-header.svelte';
	import favicon from '#lib/assets/favicon.svg';

	let { children }: LayoutProps = $props();
</script>

<svelte:head>
	<link rel="icon" href={favicon} />
</svelte:head>

<SiteHeader />
<main class="mx-auto w-full max-w-5xl px-5">
	{@render children()}
</main>
<SiteFooter />
