<script lang="ts">
	import { SITE } from '#lib/site';

	const year = new Date().getFullYear();
</script>

<footer class="site-footer">
	<div class="container site-footer__inner">
		<p>© {SITE.startYear}–{year} {SITE.name} · 内容先成为 HTML，交互按需出现。</p>
		<div class="footer-links">
			<a href="/rss.xml">RSS</a>
			<a href="/sitemap.xml">Sitemap</a>
			<a href="/blog/">归档</a>
			<a href="/about/">关于</a>
		</div>
	</div>
</footer>
