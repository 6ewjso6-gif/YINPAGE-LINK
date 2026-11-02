<script lang="ts">
	import { onDestroy } from 'svelte';
	import Badge from '#lib/components/ui/badge.svelte';
	import Button from '#lib/components/ui/button.svelte';
	import Card from '#lib/components/ui/card.svelte';
	import SegmentedControl from '#lib/components/ui/segmented-control.svelte';
	import Slider from '#lib/components/ui/slider.svelte';
	import { site } from '#lib/site';
	import { cn } from '#lib/utils';

	type Format = 'image/webp' | 'image/jpeg' | 'image/png';
	type ItemStatus = 'ready' | 'working' | 'done' | 'error';

	type Item = {
		id: string;
		file: File;
		srcUrl: string;
		name: string;
		width: number;
		height: number;
		status: ItemStatus;
		error?: string;
		result?: { url: string; size: number; ext: string };
	};

	const MAX_FILES = 12;
	const MAX_SIZE = 25 * 1024 * 1024;

	const formatOptions = [
		{ value: 'image/webp', label: 'WebP' },
		{ value: 'image/jpeg', label: 'JPEG' },
		{ value: 'image/png', label: 'PNG' }
	];

	const extMap: Record<Format, string> = {
		'image/webp': 'webp',
		'image/jpeg': 'jpg',
		'image/png': 'png'
	};

	const formatNotes: Record<Format, string> = {
		'image/webp': '体积通常最小，支持透明，现代浏览器都认。',
		'image/jpeg': '兼容性最好，但不支持透明，透明区域会填成白色。',
		'image/png': '无损、支持透明，文件通常最大。'
	};

	let items = $state<Item[]>([]);
	let format = $state<Format>('image/webp');
	let quality = $state(85);
	let dragActive = $state(false);
	let message = $state('');
	let fileInput: HTMLInputElement | undefined = $state();

	let lastFormat: Format | undefined;
	let lastQuality: number | undefined;

	$effect(() => {
		if (lastFormat === undefined) {
			lastFormat = format;
			lastQuality = quality;
			return;
		}

		if (lastFormat !== format || lastQuality !== quality) {
			lastFormat = format;
			lastQuality = quality;
			resetResults();
		}
	});

	let busy = $derived(items.some((item) => item.status === 'working'));
	let totalOriginal = $derived(items.reduce((sum, item) => sum + item.file.size, 0));
	let readyCount = $derived(items.filter((item) => item.status !== 'done').length);

	function formatSize(bytes: number): string {
		if (bytes < 1024) return `${bytes} B`;
		if (bytes < 1024 * 1024) {
			return `${(bytes / 1024).toFixed(bytes < 102400 ? 1 : 0)} KB`;
		}
		return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
	}

	function loadImage(url: string): Promise<HTMLImageElement> {
		return new Promise((resolve, reject) => {
			const image = new Image();
			image.onload = () => resolve(image);
			image.onerror = () => reject(new Error('图片无法解码'));
			image.src = url;
		});
	}

	function baseName(name: string): string {
		return name.replace(/\.(png|jpe?g|webp|gif|avif|bmp)$/i, '') || 'image';
	}

	function savingsLabel(item: Item): string {
		if (!item.result) return '';
		const delta = Math.round((1 - item.result.size / item.file.size) * 100);
		if (delta > 0) return `省 ${delta}%`;
		if (delta < 0) return `大 ${-delta}%`;
		return '持平';
	}

	function revoke(item: Item) {
		URL.revokeObjectURL(item.srcUrl);
		if (item.result) URL.revokeObjectURL(item.result.url);
	}

	function resetResults() {
		for (const item of items) {
			if (item.result) URL.revokeObjectURL(item.result.url);
			item.result = undefined;
			if (item.status === 'done' || item.status === 'error') {
				item.status = 'ready';
				item.error = undefined;
			}
		}
	}

	async function addFiles(list: FileList | null) {
		message = '';
		if (!list?.length) return;

		const files = Array.from(list).filter((file) => file.type.startsWith('image/'));
		if (!files.length) {
			message = '只支持图片文件。';
			return;
		}
		if (items.length + files.length > MAX_FILES) {
			message = `一次最多处理 ${MAX_FILES} 张图片。`;
			return;
		}

		for (const file of files) {
			if (file.size > MAX_SIZE) {
				message = `「${file.name}」超过 25 MB，已跳过。`;
				continue;
			}

			const srcUrl = URL.createObjectURL(file);
			try {
				const image = await loadImage(srcUrl);
				items.push({
					id: crypto.randomUUID(),
					file,
					srcUrl,
					name: file.name,
					width: image.naturalWidth,
					height: image.naturalHeight,
					status: 'ready'
				});
			} catch {
				URL.revokeObjectURL(srcUrl);
				message = `「${file.name}」无法读取。`;
			}
		}

		if (fileInput) fileInput.value = '';
	}

	async function convert(index: number) {
		const item = items[index];
		if (!item || item.status === 'working') return;

		item.status = 'working';
		item.error = undefined;

		try {
			const image = await loadImage(item.srcUrl);
			const canvas = document.createElement('canvas');
			canvas.width = image.naturalWidth;
			canvas.height = image.naturalHeight;

			const context = canvas.getContext('2d');
			if (!context) throw new Error('当前浏览器不支持画布');

			if (format === 'image/jpeg') {
				context.fillStyle = '#ffffff';
				context.fillRect(0, 0, canvas.width, canvas.height);
			}
			context.drawImage(image, 0, 0);

			const blob = await new Promise<Blob | null>((resolve) => {
				canvas.toBlob(resolve, format, quality / 100);
			});
			if (!blob) throw new Error('转换失败，可能是浏览器不支持该格式');

			if (item.result) URL.revokeObjectURL(item.result.url);
			item.result = { url: URL.createObjectURL(blob), size: blob.size, ext: extMap[format] };
			item.status = 'done';
		} catch (error) {
			item.status = 'error';
			item.error = error instanceof Error ? error.message : '转换失败';
		}
	}

	async function convertAll() {
		for (let index = 0; index < items.length; index++) {
			if (items[index].status !== 'done') await convert(index);
		}
	}

	function remove(index: number) {
		const item = items[index];
		if (!item) return;
		revoke(item);
		items.splice(index, 1);
	}

	function clearAll() {
		for (const item of items) revoke(item);
		items = [];
		message = '';
	}

	function download(item: Item) {
		if (!item.result) return;
		const link = document.createElement('a');
		link.href = item.result.url;
		link.download = `${baseName(item.name)}.${item.result.ext}`;
		document.body.appendChild(link);
		link.click();
		link.remove();
	}

	function onDrop(event: DragEvent) {
		event.preventDefault();
		dragActive = false;
		void addFiles(event.dataTransfer?.files ?? null);
	}

	onDestroy(() => {
		for (const item of items) revoke(item);
	});
</script>

<svelte:head>
	<title>图片格式转换 · {site.name}</title>
	<meta
		name="description"
		content="在浏览器里把 PNG、JPEG、WebP 互相转换，支持批量、质量调节和体积对比，文件不会上传。"
	/>
</svelte:head>

<section class="pt-10 pb-8">
	<div class="flex flex-wrap items-center gap-2">
		<Badge tone="brand">图片工具</Badge>
		<Badge>纯本地处理</Badge>
	</div>
	<h1 class="mt-4 text-2xl font-semibold tracking-tight sm:text-3xl">图片格式转换</h1>
	<p class="mt-3 max-w-xl text-sm leading-relaxed text-muted-foreground">
		选择 PNG、JPEG 或 WebP 图片，转换成需要的格式再下载。文件只在这台设备上处理，不会上传。
	</p>
</section>

<div class="grid items-start gap-5 pb-6 lg:grid-cols-[minmax(0,1fr)_320px]">
	<Card className="p-4 sm:p-5">
		<div
			role="group"
			aria-label="图片上传区域"
			class={cn(
				'rounded-[10px] border border-dashed transition-colors',
				dragActive ? 'border-brand bg-brand-soft' : 'border-border bg-surface-muted/40'
			)}
			ondragover={(event) => {
				event.preventDefault();
				dragActive = true;
			}}
			ondragleave={() => (dragActive = false)}
			ondrop={onDrop}
		>
			<input
				id="image-input"
				bind:this={fileInput}
				type="file"
				accept="image/*"
				multiple
				class="sr-only"
				onchange={(event) => void addFiles(event.currentTarget.files)}
			/>
			<label
				for="image-input"
				class="flex cursor-pointer flex-col items-center gap-2 px-4 py-10 text-center sm:py-12"
			>
				<span
					class="flex h-10 w-10 items-center justify-center rounded-full bg-background text-muted-foreground ring-1 ring-border"
				>
					<svg
						viewBox="0 0 24 24"
						class="h-5 w-5"
						fill="none"
						stroke="currentColor"
						stroke-width="1.6"
						aria-hidden="true"
					>
						<path d="M12 16V4M7 9l5-5 5 5" />
						<path d="M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3" />
					</svg>
				</span>
				<span class="text-sm font-medium">选择图片，或拖到这里</span>
				<span class="text-xs text-muted-foreground tabular-nums">
					PNG / JPEG / WebP · 单张 25 MB · 最多 {MAX_FILES} 张
				</span>
			</label>
		</div>

		{#if message}
			<p
				class="mt-3 rounded-lg border border-danger/30 bg-danger-soft px-3 py-2 text-[13px] text-danger"
				role="alert"
			>
				{message}
			</p>
		{/if}

		{#if items.length}
			<div class="mt-5 flex items-center justify-between gap-4">
				<p class="text-[13px] text-muted-foreground tabular-nums">
					已选 {items.length} 张 · 原始 {formatSize(totalOriginal)}
				</p>
				<button
					type="button"
					onclick={clearAll}
					disabled={busy}
					class="text-[13px] text-muted-foreground transition-colors hover:text-danger disabled:opacity-50"
				>
					清空
				</button>
			</div>

			<ul class="mt-2 divide-y divide-border border-t border-border">
				{#each items as item, index (item.id)}
					<li class="flex items-start gap-3 py-3">
						<img
							src={item.srcUrl}
							alt=""
							class="h-12 w-12 shrink-0 rounded-md border border-border bg-surface-muted object-cover"
						/>

						<div class="min-w-0 flex-1">
							<div class="flex flex-wrap items-center gap-2">
								<p class="truncate text-[13px] font-medium">{item.name}</p>
								{#if item.status === 'working'}
									<Badge>处理中</Badge>
								{:else if item.status === 'done'}
									<Badge tone="success">完成</Badge>
								{:else if item.status === 'error'}
									<Badge tone="danger">失败</Badge>
								{/if}
							</div>

							<p class="mt-0.5 text-xs text-muted-foreground tabular-nums">
								{item.width} × {item.height} · {formatSize(item.file.size)}
								{#if item.result}
									<span class="text-foreground/80">
										→ {formatSize(item.result.size)} · {savingsLabel(item)}
									</span>
								{/if}
							</p>

							{#if item.error}
								<p class="mt-1 text-xs text-danger">{item.error}</p>
							{/if}
						</div>

						<div class="flex shrink-0 items-center gap-1">
							{#if item.status === 'done' && item.result}
								<Button size="sm" variant="secondary" onclick={() => download(item)}>
									下载
								</Button>
							{:else}
								<Button
									size="sm"
									variant="ghost"
									disabled={item.status === 'working'}
									onclick={() => void convert(index)}
								>
									转换
								</Button>
							{/if}

							<button
								type="button"
								aria-label={`移除 ${item.name}`}
								onclick={() => remove(index)}
								disabled={item.status === 'working'}
								class="inline-flex h-8 w-8 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-surface-muted hover:text-foreground disabled:opacity-40"
							>
								<svg
									viewBox="0 0 24 24"
									class="h-4 w-4"
									fill="none"
									stroke="currentColor"
									stroke-width="1.6"
									aria-hidden="true"
								>
									<path d="M6 6l12 12M18 6 6 18" />
								</svg>
							</button>
						</div>
					</li>
				{/each}
			</ul>
		{/if}
	</Card>

	<aside class="lg:sticky lg:top-20">
		<Card className="p-5">
			<h2 class="text-sm font-semibold tracking-tight">输出设置</h2>

			<div class="mt-4 grid gap-2">
				<span class="text-[13px] font-medium">格式</span>
				<SegmentedControl bind:value={format} options={formatOptions} className="w-full" />
				<p class="text-xs leading-relaxed text-muted-foreground">
					{formatNotes[format]}
				</p>
			</div>

			{#if format !== 'image/png'}
				<div class="mt-5 border-t border-border pt-4">
					<Slider
						bind:value={quality}
						label="质量"
						display={`${quality}%`}
						min={40}
						max={100}
						step={5}
					/>
					<p class="mt-2 text-xs leading-relaxed text-muted-foreground">
						质量越高越清晰，文件也越大。Web 分享通常 80% 就够。
					</p>
				</div>
			{/if}

			<div class="mt-6 grid gap-2">
				<Button
					disabled={items.length === 0 || busy || readyCount === 0}
					onclick={() => void convertAll()}
				>
					{busy ? '正在转换…' : `转换全部${readyCount > 0 ? `（${readyCount}）` : ''}`}
				</Button>
				<p class="text-center text-xs text-muted-foreground">
					转换在本地完成，照片不会离开这台设备。
				</p>
			</div>
		</Card>
	</aside>
</div>
