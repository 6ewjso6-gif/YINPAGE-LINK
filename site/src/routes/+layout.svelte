<script lang="ts">
	import '../app.css';
	import type { Snippet } from 'svelte';
	import { SITE } from '#lib/site';
	import SiteHeader from '#lib/components/SiteHeader.svelte';
	import SiteFooter from '#lib/components/SiteFooter.svelte';

	let { children }: { children: Snippet } = $props();
</script>

<svelte:head>
	<title>{SITE.name} — {SITE.tagline}</title>
	<meta name="description" content={SITE.description} />
	<meta property="og:title" content="{SITE.name} — {SITE.tagline}" />
	<meta property="og:description" content={SITE.description} />
	<meta property="og:type" content="website" />
	<meta property="og:url" content={SITE.url} />
	<link rel="alternate" type="application/rss+xml" title="{SITE.name} RSS" href="/rss.xml" />
</svelte:head>

<a class="skip-link" href="#main">跳到正文</a>
<SiteHeader />
<main id="main" class="page">
	{@render children()}
</main>
<SiteFooter />
