export const site = {
	name: '百工',
	title: '百工 · 在线工具集',
	description: '打开就能用的轻量工具集。文件只在你的浏览器里处理，不上传服务器。',
	url: 'https://zzz.denia.dpdns.org',
	nav: [
		{ href: '/', label: '首页' },
		{ href: '/tools', label: '工具' },
		{ href: '/about', label: '关于' }
	]
} as const;

export type ToolStatus = 'ready' | 'building';

export type Tool = {
	slug: string;
	name: string;
	summary: string;
	description: string;
	status: ToolStatus;
	href?: string;
	tags: string[];
};

export const tools: Tool[] = [
	{
		slug: 'image-convert',
		name: '图片格式转换',
		summary: 'PNG、JPEG、WebP 互转，可调质量',
		description:
			'选择本地图片，转换成 PNG、JPEG 或 WebP 并下载。支持批量、质量调节和体积对比，整个过程在浏览器内完成。',
		status: 'ready',
		href: '/tools/image-convert',
		tags: ['图片', '本地处理']
	},
	{
		slug: 'image-compress',
		name: '图片压缩',
		summary: '按目标体积压缩图片',
		description: '为上传限制做准备：指定目标大小，自动寻找可接受的质量。',
		status: 'building',
		tags: ['图片', '规划中']
	},
	{
		slug: 'cover-generator',
		name: '封面生成',
		summary: '文章封面与社交卡片',
		description: '用统一模板批量生成标题封面，导出为图片。',
		status: 'building',
		tags: ['设计', '规划中']
	}
];
