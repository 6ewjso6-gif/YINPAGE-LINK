<script lang="ts">
	import Button from '#lib/components/ui/button.svelte';

	type EditorPost = {
		slug: string;
		title: string;
		summary: string;
		body: string;
		tags: string[];
		published: boolean;
	} | null;

	type Values = {
		slug?: string;
		title?: string;
		summary?: string;
		body?: string;
		tags?: string;
		published?: boolean;
	} | null;

	let {
		post = null,
		values = null,
		error = ''
	}: {
		post?: EditorPost;
		values?: Values;
		error?: string;
	} = $props();

	const field =
		'w-full rounded-lg border border-border bg-surface px-3 text-sm outline-none placeholder:text-muted-foreground focus:border-brand/60';

	const slugPattern = '[a-z0-9][a-z0-9-]{0,63}';

	let tagsValue = $derived(values?.tags ?? post?.tags.join(', ') ?? '');
	let published = $derived(values?.published ?? post?.published ?? true);
</script>

<form method="POST" action="?/save" class="grid gap-5">
	{#if error}
		<p class="rounded-lg border border-danger/30 bg-danger/10 px-3 py-2 text-[13px] text-danger">
			{error}
		</p>
	{/if}

	<label class="grid gap-1.5">
		<span class="text-xs text-muted-foreground">标题</span>
		<input
			name="title"
			required
			value={values?.title ?? post?.title ?? ''}
			placeholder="文章标题"
			class="{field} h-10"
		/>
	</label>

	<label class="grid gap-1.5">
		<span class="text-xs text-muted-foreground">网址（slug）</span>
		<input
			name="slug"
			required
			pattern={slugPattern}
			value={values?.slug ?? post?.slug ?? ''}
			placeholder="小写字母、数字、连字符"
			class="{field} h-10 font-mono"
		/>
		<span class="text-[11px] text-muted-foreground">
			发布后地址是 /blog/<span class="text-foreground">slug</span>，改 slug 会改变文章链接。
		</span>
	</label>

	<div class="grid gap-4 sm:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
		<label class="grid gap-1.5">
			<span class="text-xs text-muted-foreground">摘要</span>
			<input
				name="summary"
				value={values?.summary ?? post?.summary ?? ''}
				placeholder="列表里显示的一句话"
				class="{field} h-10"
			/>
		</label>

		<label class="grid gap-1.5">
			<span class="text-xs text-muted-foreground">标签（逗号分隔）</span>
			<input
				name="tags"
				value={tagsValue}
				placeholder="前端, 架构"
				class="{field} h-10"
			/>
		</label>
	</div>

	<label class="grid gap-1.5">
		<span class="text-xs text-muted-foreground">正文（Markdown）</span>
		<textarea
			name="body"
			rows={18}
			required
			placeholder="## 小标题&#10;&#10;正文…"
			class="{field} resize-y py-3 leading-relaxed"
		>{values?.body ?? post?.body ?? ''}</textarea>
	</label>

	<label class="flex items-center gap-2 text-sm">
		<input type="checkbox" name="published" checked={published} />
		发布（取消勾选则保存为草稿）
	</label>

	<div class="flex items-center gap-3">
		<Button type="submit">{post ? '保存修改' : '创建文章'}</Button>
		<Button href="/admin" variant="secondary">取消</Button>
	</div>
</form>
