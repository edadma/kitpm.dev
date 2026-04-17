// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

// https://astro.build/config
export default defineConfig({
	site: 'https://kitpm.dev',
	integrations: [
		starlight({
			title: 'Kit',
			social: [{ icon: 'github', label: 'GitHub', href: 'https://github.com/edadma/kit' }],
			sidebar: [
				{
					label: 'Getting Started',
					items: [
						{ label: 'Introduction', slug: 'getting-started/introduction' },
					],
				},
				{
					label: 'Design',
					items: [
						{ label: 'Overview', slug: 'design/overview' },
					],
				},
			],
		}),
	],
});
