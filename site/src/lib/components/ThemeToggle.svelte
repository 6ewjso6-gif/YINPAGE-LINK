<script lang="ts">
	import { onMount } from 'svelte';

	let dark = $state(false);
	let ready = $state(false);

	onMount(() => {
		ready = true;
		dark = document.documentElement.dataset.theme === 'dark';
	});

	function toggle() {
		dark = !dark;
		document.documentElement.dataset.theme = dark ? 'dark' : 'light';
		try {
			localStorage.setItem('theme', dark ? 'dark' : 'light');
		} catch {
			/* 忽略隐私模式下的存储失败 */
		}
	}
</script>

<button
	class="icon-button"
	type="button"
	aria-label={dark ? '切换到浅色主题' : '切换到深色主题'}
	aria-pressed={dark}
	onclick={toggle}
>
	{#if !ready || dark}
		<svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
			<circle cx="12" cy="12" r="4.2" stroke="currentColor" stroke-width="1.6" />
			<path
				d="M12 2.6v2.2M12 19.2v2.2M2.6 12h2.2M19.2 12h2.2M5.4 5.4l1.6 1.6M17 17l1.6 1.6M18.6 5.4L17 7M7 17l-1.6 1.6"
				stroke="currentColor"
				stroke-width="1.6"
				stroke-linecap="round"
			/>
		</svg>
	{:else}
		<svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
			<path
				d="M20.5 14.2A8.6 8.6 0 0 1 9.8 3.5a8.6 8.6 0 1 0 10.7 10.7Z"
				stroke="currentColor"
				stroke-width="1.6"
				stroke-linejoin="round"
			/>
		</svg>
	{/if}
</button>
