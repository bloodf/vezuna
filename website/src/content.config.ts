import { defineCollection } from 'astro:content';
import { glob } from 'astro/loaders';
import { z } from 'astro/zod';

const docs = defineCollection({
  loader: glob({ pattern: '**/*.md', base: './src/data/docs' }),
  schema: z.object({
    title: z.string(),
    description: z.string(),
    lang: z.enum(['en', 'pt-br', 'es']),
    order: z.number(),
    audience: z.enum(['user', 'developer']),
    translationKey: z.string(),
    essential: z.boolean().default(false),
  }),
});

export const collections = { docs };
