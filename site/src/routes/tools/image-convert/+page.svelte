<script lang="ts">
	import { onDestroy } from 'svelte';

	type OutputFormat = 'image/webp' | 'image/jpeg' | 'image/png';

	const formats: { value: OutputFormat; label: string; ext: string }[] = [
		{ value: 'image/webp', label: 'WebP（推荐）', ext: 'webp' },
		{ value: 'image/jpeg', label: 'JPEG', ext: 'jpg' },
		{ value: 'image/png', label: 'PNG（无损）', ext: 'png' }
	];

	const accepted = ['image/png', 'image/jpeg', 'image/webp', 'image/gif'];

	let file = $state<File | null>(null);
	let sourceUrl = $state('');
	let resultUrl = $state('');
	let resultSize = $state(0);
	let outputSize = $state({ width: 0, height: 0 });
	let sourceSize = $state({ width: 0, height: 0 });
	let format = $state<OutputFormat>('image/webp');
	let quality = $state(82);
	let maxWidth = $state(1600);
	let status = $state<'idle' | 'working' | 'done' | 'error'>('idle');
	let errorMessage = $state('');
	let dragging = $state(false);
	let input = $state<HTMLInputElement | null>(null);

	const outputName = $derived(
		file
			? `${file.name.replace(/\.[^.]+$/, '')}.${formats.find((item) => item.value === format)?.ext}`
			: ''
	);

	const saving = $derived(
		file && resultSize > 0 ? Math.round((1 - resultSize / file.size) * 100) : 0
	);

	function formatBytes(bytes: number): string {
		if (bytes < 1024) return `${bytes} B`;
		if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
		return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
	}

	function release() {
		if (sourceUrl) URL.revokeObjectURL(sourceUrl);
		if (resultUrl) URL.revokeObjectURL(resultUrl);
		sourceUrl = '';
		resultUrl = '';
	}

	function handleFile(selected: File) {
		if (!accepted.includes(selected.type)) {
			status = 'error';
			errorMessage = '请选择 PNG、JPEG、WebP 或 GIF 图片。';
			return;
		}

		release();
		file = selected;
		resultSize = 0;
		sourceUrl = URL.createObjectURL(selected);
		status = 'working';
		errorMessage = '';
		convert();
	}

	function onDrop(event: DragEvent) {
		event.preventDefault();
		dragging = false;
		const dropped = event.dataTransfer?.files?.[0];
		if (dropped) handleFile(dropped);
	}

	function onPick(event: Event) {
		const selected = (event.currentTarget as HTMLInputElement).files?.[0];
		if (selected) handleFile(selected);
	}

	async function convert() {
		if (!file) return;
		status = 'working';
		errorMessage = '';

		try {
			const bitmap = await createImageBitmap(file);
			sourceSize = { width: bitmap.width, height: bitmap.height };

			const scale = Math.min(1, maxWidth / bitmap.width);
			const width = Math.max(1, Math.round(bitmap.width * scale));
			const height = Math.max(1, Math.round(bitmap.height * scale));

			const canvas = document.createElement('canvas');
			canvas.width = width;
			canvas.height = height;

			const context = canvas.getContext('2d');
			if (!context) throw new Error('当前浏览器不支持 Canvas 2D。');

			if (format === 'image/jpeg') {
				context.fillStyle = '#ffffff';
				context.fillRect(0, 0, width, height);
			}

			context.drawImage(bitmap, 0, 0, width, height);
			bitmap.close();

			const blob = await new Promise<Blob | null>((resolve) =>
				canvas.toBlob(resolve, format, quality / 100)
			);
			if (!blob) throw new Error('浏览器未能生成图片，请换一种格式再试。');

			if (resultUrl) URL.revokeObjectURL(resultUrl);
			resultUrl = URL.createObjectURL(blob);
			resultSize = blob.size;
			outputSize = { width, height };
			status = 'done';
		} catch (error) {
			status = 'error';
			errorMessage =
				error instanceof Error ? error.message : '转换失败，请换一张图片或换一种格式。';
		}
	}

	onDestroy(release);
</script>

<svelte:head>
	<title>图片格式转换 — 工具</title>
	<meta name="description" content="在浏览器本地把图片转换为 WebP、JPEG 或 PNG，可调整质量与最大宽度，文件不会上传。" />
</svelte:head>

<div class="container narrow">
	<p class="eyebrow">工具 / 图片</p>
	<h1 style="font: 600 30px/1.4 var(--font-serif)">图片格式转换</h1>
	<p class="muted" style="margin-top: var(--space-3); max-width: 560px">
		转换在浏览器里完成，文件不会上传。适合把截图压成 WebP，或把透明图换成 JPEG。
	</p>

	<div class="tool-shell">
		<div
			class="dropzone"
			role="button"
			tabindex="0"
			data-dragging={dragging}
			ondragover={(event) => {
				event.preventDefault();
				dragging = true;
			}}
			ondragleave={() => (dragging = false)}
			ondrop={onDrop}
			onclick={() => input?.click()}
			onkeydown={(event) => {
				if (event.key === 'Enter' || event.key === ' ') {
					event.preventDefault();
					input?.click();
				}
			}}
		>
			<svg width="26" height="26" viewBox="0 0 24 24" fill="none" aria-hidden="true">
				<path d="M12 16V5m0 0-4 4m4-4 4 4" stroke="currentColor" stroke-width="1.6" />
				<path d="M5 17v2a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-2" stroke="currentColor" stroke-width="1.6" />
			</svg>
			<h3>{status === 'working' ? '正在转换…' : '选择图片，或拖到这里'}</h3>
			<p>支持 PNG / JPEG / WebP / GIF，单张处理，不会上传。</p>
			<input bind:this={input} type="file" accept="image/png,image/jpeg,image/webp,image/gif" onchange={onPick} />
		</div>

		{#if status === 'error'}
			<p class="notice" data-tone="error">{errorMessage}</p>
		{/if}

		<div class="tool-controls">
			<div class="field">
				<label for="format">输出格式</label>
				<select id="format" bind:value={format} onchange={() => file && convert()}>
					{#each formats as item (item.value)}
						<option value={item.value}>{item.label}</option>
					{/each}
				</select>
			</div>

			<div class="field">
				<label for="quality">
					质量 <span class="field__value">{quality}%</span>
				</label>
				<input
					id="quality"
					type="range"
					min="30"
					max="100"
					step="1"
					bind:value={quality}
					disabled={format === 'image/png'}
					onchange={() => file && convert()}
				/>
			</div>

			<div class="field">
				<label for="width">最大宽度 <span class="field__value">{maxWidth}px</span></label>
				<input
					id="width"
					type="number"
					min="120"
					max="6000"
					step="10"
					bind:value={maxWidth}
					onchange={() => file && convert()}
				/>
			</div>
		</div>

		{#if format === 'image/png'}
			<p class="notice" data-tone="info">PNG 为无损格式，质量设置对它不生效；体积通常大于 WebP。</p>
		{/if}

		{#if status === 'done' && file}
			<div class="result">
				<div class="result__preview">
					<img src={resultUrl} alt="转换后的预览" />
				</div>
				<div class="result__facts">
					<dl>
						<dt>文件</dt>
						<dd>{outputName}</dd>
						<dt>原始体积</dt>
						<dd>{formatBytes(file.size)}</dd>
						<dt>输出体积</dt>
						<dd>
							{formatBytes(resultSize)}
							{#if saving > 0}<span style="color: var(--accent)">（−{saving}%）</span>{:else}
								<span>（+{Math.abs(saving)}%）</span>
							{/if}
						</dd>
						<dt>尺寸</dt>
						<dd>{sourceSize.width} × {sourceSize.height} → {outputSize.width} × {outputSize.height}</dd>
					</dl>
					<div class="result__actions">
						<a class="btn btn--primary" href={resultUrl} download={outputName}>下载图片</a>
						<button
							class="btn btn--ghost"
							type="button"
							onclick={() => {
								input && (input.value = '');
								file = null;
								release();
								status = 'idle';
							}}
						>
							换一张
						</button>
					</div>
				</div>
			</div>
		{/if}
	</div>

	<p class="muted" style="margin-top: var(--space-4); font-size: var(--text-xs); line-height: 1.7">
		提示：JPEG 不支持透明，透明区域会被填成白色。GIF 只输出第一帧。
	</p>
</div>
