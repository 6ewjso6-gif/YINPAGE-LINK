<script lang="ts">
	import { SITE } from '#lib/site';
	import ThemeToggle from './ThemeToggle.svelte';
</script>

<header class="site-header">
	<div class="container site-header__inner">
		<a class="brand" href="/">
			<span class="brand__mark">手</span>
			<span>{SITE.name}</span>
		</a>
		<nav class="site-nav" aria-label="主导航">
			{#each SITE.nav as item (item.href)}
				<a href={item.href}>{item.label}</a>
			{/each}
		</nav>
		<div class="header-actions">
			<ThemeToggle />
		</div>
	</div>
</header>
