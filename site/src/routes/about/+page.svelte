<svelte:head>
	<title>关于 — 手记</title>
	<meta name="description" content="关于这个站点，以及它为什么这样搭建。" />
</svelte:head>

<div class="container narrow">
	<p class="eyebrow">关于</p>
	<h1 style="font: 600 var(--text-2xl) var(--font-serif)">一个可以慢慢长大的站点</h1>

	<div class="prose" style="margin-top: var(--space-8)">
		<p>
			这里放文章和工具。文章讲前端、产品与建造过程；工具尽量在浏览器本地完成，不上传文件。
		</p>
		<p>站点本身也按文章里写的方式搭建：</p>
		<ul>
			<li>内容在构建期变成 HTML，浏览器拿到的不是空容器；</li>
			<li>Markdown、代码高亮、目录都在构建期完成；</li>
			<li>RSS、Sitemap、搜索索引来自同一份内容源；</li>
			<li>JavaScript 只负责搜索、筛选和工具里的真实交互。</li>
		</ul>

		<blockquote>先让内容成为 HTML，再让交互按需出现。</blockquote>

		<h2>联系</h2>
		<p>把这里的邮箱换成你的即可：<a href="mailto:hello@example.com">hello@example.com</a>。</p>
	</div>
</div>
