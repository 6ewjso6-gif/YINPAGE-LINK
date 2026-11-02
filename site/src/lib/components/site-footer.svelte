<script lang="ts">
	import { site } from '#lib/site';

	const year = new Date().getFullYear();
</script>

<footer class="mt-20 border-t border-border">
	<div
		class="mx-auto flex w-full max-w-5xl flex-col gap-3 px-5 py-8 text-[13px] text-muted-foreground sm:flex-row sm:items-center sm:justify-between"
	>
		<p>© {year} {site.name}</p>
		<div class="flex flex-wrap items-center gap-x-5 gap-y-2">
			<a href="/tools" class="hover:text-foreground">工具</a>
			<a href="/about" class="hover:text-foreground">关于</a>
			<a href="/sitemap.xml" class="hover:text-foreground">Sitemap</a>
		</div>
	</div>
</footer>
