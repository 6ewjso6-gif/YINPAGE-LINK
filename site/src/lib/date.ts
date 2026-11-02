export function formatDate(value: string): string {
	if (!value) return '';
	const [year, month, day] = value.slice(0, 10).split('-');
	if (!year || !month || !day) return value;
	return `${year} 年 ${Number(month)} 月 ${Number(day)} 日`;
}
