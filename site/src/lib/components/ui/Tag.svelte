<script lang="ts">
	let { tag, active = false }: { tag: string; active?: boolean } = $props();
</script>

<a class="chip" data-active={active} href="/tags/{encodeURIComponent(tag)}/">#{tag}</a>
