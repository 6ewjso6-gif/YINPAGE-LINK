<svelte:head>
	<title>工具 — 能用的那种</title>
	<meta name="description" content="在浏览器本地运行的小工具，不上传文件，不依赖后端。" />
</svelte:head>

<div class="container">
	<p class="eyebrow">工具</p>
	<h1 style="font: 600 var(--text-2xl) var(--font-serif)">能用的那种</h1>
	<p class="muted" style="margin-top: var(--space-3); max-width: 560px">
		优先做完整一个，再复制结构。所有工具都在浏览器本地运行，文件不会离开你的设备。
	</p>

	<div class="tool-grid">
		<a class="tool-card" href="/tools/image-convert/">
			<span class="tool-card__icon" aria-hidden="true">
				<svg width="18" height="18" viewBox="0 0 24 24" fill="none">
					<rect x="3" y="4" width="18" height="16" rx="2" stroke="currentColor" stroke-width="1.6" />
					<circle cx="8.5" cy="9.5" r="1.4" fill="currentColor" />
					<path d="m4 18 5-5 4 4 3-3 4 4" stroke="currentColor" stroke-width="1.6" />
				</svg>
			</span>
			<h3>图片格式转换</h3>
			<p>PNG、JPEG、WebP 互转，可调质量与最大宽度。支持拖拽，输出前可以看到体积变化。</p>
			<span class="tool-card__foot">可用 · 纯前端 · 无上传</span>
		</a>

		<article class="tool-card" data-status="planned">
			<span class="tool-card__icon" aria-hidden="true">
				<svg width="18" height="18" viewBox="0 0 24 24" fill="none">
					<path d="M12 4v16M4 12h16" stroke="currentColor" stroke-width="1.6" />
				</svg>
			</span>
			<h3>下一个工具</h3>
			<p>需要一个不需要后端、输入输出明确、用户能立刻判断结果的能力。想好之后放这里。</p>
			<span class="tool-card__foot">规划中</span>
		</article>
	</div>

	<section class="section">
		<div class="section-head">
			<h2>工具的原则</h2>
		</div>
		<div class="card" style="margin-top: var(--space-4)">
			<p class="muted" style="font-size: 14px; line-height: 1.8">
				能离线就离线；不能离线时，先明确告诉用户需要什么。加载、失败、空状态和结果都要有，而不是只画一个成功路径。
			</p>
		</div>
	</section>
</div>
