export function formatDate(date: string): string {
	const [year, month, day] = date.split('-');
	return `${year} 年 ${Number(month)} 月 ${Number(day)} 日`;
}
