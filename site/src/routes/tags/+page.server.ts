import { getTags } from '#lib/server/posts';

export function load() {
	return { tags: getTags() };
}
