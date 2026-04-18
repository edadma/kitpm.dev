// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

// https://astro.build/config
export default defineConfig({
	site: 'https://kitpm.dev',
	integrations: [
		starlight({
			title: 'Kit',
			social: [{ icon: 'github', label: 'GitHub', href: 'https://github.com/edadma/kitpm.dev' }],
			sidebar: [
				{
					label: 'Getting Started',
					items: [
						{ label: 'Introduction', slug: 'getting-started/introduction' },
						{ label: 'Quick Start', slug: 'getting-started/quick-start' },
					],
				},
				{
					label: 'Concepts',
					items: [
						{ label: 'Store', slug: 'concepts/store' },
						{ label: 'Profiles & Generations', slug: 'concepts/profiles' },
						{ label: 'Effects', slug: 'concepts/effects' },
						{ label: 'Adapters', slug: 'concepts/adapters' },
						{ label: 'Package Testing', slug: 'concepts/testing' },
					],
				},
				{
					label: 'Reference',
					items: [
						{ label: 'CLI', slug: 'reference/cli' },
						{ label: 'Package Format', slug: 'reference/package-format' },
						{ label: 'On-Disk Layout', slug: 'reference/layout' },
					],
				},
				{
					label: 'Design',
					items: [
						{ label: 'Overview', slug: 'design/overview' },
						{ label: 'Invariants', slug: 'design/invariants' },
						{ label: 'Comparison to Nix', slug: 'design/comparison' },
					],
				},
			],
		}),
	],
});
