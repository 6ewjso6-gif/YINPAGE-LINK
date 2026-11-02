<script lang="ts">
	import type { PageProps } from './$types';
	import Badge from '#lib/components/ui/badge.svelte';
	import Button from '#lib/components/ui/button.svelte';
	import { formatDate } from '#lib/date';

	let { data }: PageProps = $props();
</script>

<svelte:head>
	<title>后台 · 文章与评论</title>
	<meta name="robots" content="noindex" />
</svelte:head>

<section class="flex flex-wrap items-end justify-between gap-4 pt-12 pb-8">
	<div>
		<Badge tone="brand">后台</Badge>
		<h1 class="mt-3 text-2xl font-semibold tracking-tight">文章与评论</h1>
	</div>

	<div class="flex items-center gap-2">
		<Button href="/admin/posts/new" size="sm">新建文章</Button>
		<form method="POST" action="?/logout">
			<Button variant="secondary" size="sm" type="submit">退出</Button>
		</form>
	</div>
</section>

<section class="grid gap-3 pb-10 text-sm sm:grid-cols-3">
	<div class="rounded-xl border border-border p-4">
		<p class="text-xs text-muted-foreground">文章</p>
		<p class="mt-1 text-xl font-semibold tabular-nums">{data.posts.length}</p>
	</div>
	<div class="rounded-xl border border-border p-4">
		<p class="text-xs text-muted-foreground">待审核评论</p>
		<p class="mt-1 text-xl font-semibold tabular-nums">{data.pending.length}</p>
	</div>
	<div class="rounded-xl border border-border p-4">
		<p class="text-xs text-muted-foreground">已通过评论</p>
		<p class="mt-1 text-xl font-semibold tabular-nums">{data.approved.length}</p>
	</div>
</section>

<section class="border-t border-border pt-8">
	<h2 class="text-base font-semibold tracking-tight">
		待审核<span class="ml-1.5 text-sm font-normal text-muted-foreground">{data.pending.length}</span>
	</h2>

	{#if data.pending.length}
		<ul class="mt-4 grid gap-4">
			{#each data.pending as comment (comment.id)}
				<li class="rounded-xl border border-border bg-surface p-4">
					<div class="flex flex-wrap items-baseline justify-between gap-2">
						<p class="text-sm">
							<span class="font-medium">{comment.name}</span>
							<a
								class="ml-1.5 text-xs text-muted-foreground underline"
								href={`/blog/${comment.postSlug}`}
							>
								/{comment.postSlug}
							</a>
						</p>
						<time class="text-xs text-muted-foreground">{formatDate(comment.createdAt)}</time>
					</div>
					<p class="mt-2 text-sm leading-relaxed whitespace-pre-line">{comment.body}</p>
					<div class="mt-3 flex gap-2">
						<form method="POST" action="?/approve">
							<input type="hidden" name="id" value={comment.id} />
							<Button size="sm" type="submit">通过</Button>
						</form>
						<form method="POST" action="?/deleteComment">
							<input type="hidden" name="id" value={comment.id} />
							<Button size="sm" variant="danger" type="submit">删除</Button>
						</form>
					</div>
				</li>
			{/each}
		</ul>
	{:else}
		<p class="mt-3 text-sm text-muted-foreground">没有待审核的评论。</p>
	{/if}
</section>

<section class="mt-10 border-t border-border pt-8">
	<h2 class="text-base font-semibold tracking-tight">文章</h2>

	<div class="mt-4 divide-y divide-border rounded-xl border border-border">
		{#each data.posts as post (post.slug)}
			<div class="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3">
				<div class="min-w-0 flex-1">
					<p class="truncate text-sm font-medium">{post.title}</p>
					<p class="mt-0.5 truncate text-xs text-muted-foreground">
						/blog/{post.slug} · {formatDate(post.createdAt)}
					</p>
				</div>

				<span
					class="rounded-full border px-2 py-0.5 text-[11px] {post.published
						? 'border-brand/40 bg-brand-soft text-brand'
						: 'border-border text-muted-foreground'}"
				>
					{post.published ? '已发布' : '草稿'}
				</span>

				<div class="flex items-center gap-1.5">
					<Button href={`/admin/posts/${post.slug}`} size="sm" variant="secondary">编辑</Button>

					<form method="POST" action="?/toggle">
						<input type="hidden" name="slug" value={post.slug} />
						<input type="hidden" name="next" value={post.published ? 'false' : 'true'} />
						<Button size="sm" variant="ghost" type="submit">
							{post.published ? '下架' : '发布'}
						</Button>
					</form>

					<form
						method="POST"
						action="?/deletePost"
						onsubmit={(event) => {
							if (!confirm('删除文章会连同评论一起删除，确定？')) event.preventDefault();
						}}
					>
						<input type="hidden" name="slug" value={post.slug} />
						<Button size="sm" variant="danger" type="submit">删除</Button>
					</form>
				</div>
			</div>
		{/each}
	</div>
</section>

<section class="mt-10 border-t border-border pt-8">
	<h2 class="text-base font-semibold tracking-tight">最近通过的评论</h2>

	{#if data.approved.length}
		<ul class="mt-4 grid gap-2">
			{#each data.approved as comment (comment.id)}
				<li class="flex flex-wrap items-baseline gap-x-2 text-sm">
					<span class="font-medium">{comment.name}</span>
					<span class="text-xs text-muted-foreground">/{comment.postSlug}</span>
					<span class="min-w-0 flex-1 truncate text-muted-foreground">{comment.body}</span>
					<form method="POST" action="?/hide">
						<input type="hidden" name="id" value={comment.id} />
						<Button size="sm" variant="ghost" type="submit">隐藏</Button>
					</form>
					<form method="POST" action="?/deleteComment">
						<input type="hidden" name="id" value={comment.id} />
						<Button size="sm" variant="ghost" type="submit">删除</Button>
					</form>
				</li>
			{/each}
		</ul>
	{:else}
		<p class="mt-3 text-sm text-muted-foreground">还没有通过的评论。</p>
	{/if}
</section>
