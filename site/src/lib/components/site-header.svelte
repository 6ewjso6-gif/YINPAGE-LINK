<script lang="ts">
	import { page } from '$app/state';
	import Dialog from '#lib/components/ui/dialog.svelte';
	import ThemeToggle from '#lib/components/theme-toggle.svelte';
	import { site } from '#lib/site';
	import { cn } from '#lib/utils';

	let menuOpen = $state(false);

	function isActive(href: string) {
		const path = page.url.pathname;
		return href === '/' ? path === '/' : path.startsWith(href);
	}
</script>

<header class="sticky top-0 z-40 border-b border-border bg-background/85 backdrop-blur-md">
	<div class="mx-auto flex h-16 w-full max-w-5xl items-center justify-between gap-4 px-5">
		<a href="/" class="flex items-center gap-2.5" aria-label="{site.name}首页">
			<span
				class="flex h-8 w-8 items-center justify-center rounded-lg bg-brand text-[13px] font-semibold text-brand-foreground"
			>
				百
			</span>
			<span class="text-[15px] font-semibold tracking-tight">{site.name}</span>
		</a>

		<nav class="hidden items-center gap-1 sm:flex" aria-label="主导航">
			{#each site.nav as item (item.href)}
				<a
					href={item.href}
					class={cn(
						'rounded-lg px-3 py-2 text-sm transition-colors',
						isActive(item.href)
							? 'text-foreground'
							: 'text-muted-foreground hover:text-foreground'
					)}
				>
					{item.label}
				</a>
			{/each}
		</nav>

		<div class="flex items-center gap-2">
			<div class="hidden sm:block">
				<ThemeToggle />
			</div>
			<button
				type="button"
				onclick={() => (menuOpen = true)}
				aria-label="打开菜单"
				class="inline-flex h-9 w-9 items-center justify-center rounded-lg border border-border text-muted-foreground sm:hidden"
			>
				<svg viewBox="0 0 24 24" class="h-4 w-4" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
					<path d="M4 7h16M4 12h16M4 17h16" />
				</svg>
			</button>
		</div>
	</div>
</header>

<Dialog bind:open={menuOpen} label="站点菜单">
	<div class="flex items-center justify-between">
		<span class="text-sm font-medium">{site.name}</span>
		<button
			type="button"
			onclick={() => (menuOpen = false)}
			aria-label="关闭菜单"
			class="inline-flex h-8 w-8 items-center justify-center rounded-lg text-muted-foreground hover:bg-surface-muted"
		>
			<svg viewBox="0 0 24 24" class="h-4 w-4" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
				<path d="M6 6l12 12M18 6 6 18" />
			</svg>
		</button>
	</div>
	<nav class="mt-4 grid gap-1" aria-label="移动端导航">
		{#each site.nav as item (item.href)}
			<a
				href={item.href}
				onclick={() => (menuOpen = false)}
				class={cn(
					'rounded-lg px-3 py-2.5 text-sm transition-colors',
					isActive(item.href) ? 'bg-surface-muted text-foreground' : 'text-muted-foreground'
				)}
			>
				{item.label}
			</a>
		{/each}
	</nav>
	<div class="mt-4 flex items-center justify-between border-t border-border pt-4">
		<span class="text-[13px] text-muted-foreground">外观</span>
		<ThemeToggle />
	</div>
</Dialog>
