import type { CollectionEntry } from 'astro:content';
import { localizedPath, type Alternates, type Locale } from './i18n';

export type DocEntry = CollectionEntry<'docs'>;

export function docSlug(entry: DocEntry): string {
  return entry.id.replace(/\.md$/, '').split('/').slice(1).join('/');
}

export function docPath(locale: Locale, slug: string): string {
  return localizedPath(locale, slug === 'index' ? 'docs' : `docs/${slug}`);
}

export function sortDocs(entries: DocEntry[]): DocEntry[] {
  return [...entries].sort((a, b) => a.data.order - b.data.order || a.data.title.localeCompare(b.data.title));
}

export function docAlternates(allEntries: DocEntry[], translationKey: string): Alternates {
  const alternates: Alternates = {};
  for (const entry of allEntries) {
    if (entry.data.translationKey === translationKey) {
      alternates[entry.data.lang] = docPath(entry.data.lang, docSlug(entry));
    }
  }
  return alternates;
}

export const docsUi = {
  en: { title: 'Documentation', search: 'Search the documentation', sidebar: 'Documentation pages', back: 'Back to documentation', previous: 'Previous', next: 'Next', copy: 'Copy code', copied: 'Copied', pagination: 'Previous and next page', outline: 'On this page', user: 'Using Vezuna', developer: 'Developer guide' },
  'pt-br': { title: 'Documentação', search: 'Pesquisar na documentação', sidebar: 'Páginas da documentação', back: 'Voltar à documentação', previous: 'Anterior', next: 'Próxima', copy: 'Copiar código', copied: 'Copiado', pagination: 'Página anterior e próxima', outline: 'Nesta página', user: 'Como usar o Vezuna', developer: 'Guia do desenvolvedor' },
  es: { title: 'Documentación', search: 'Buscar en la documentación', sidebar: 'Páginas de documentación', back: 'Volver a la documentación', previous: 'Anterior', next: 'Siguiente', copy: 'Copiar código', copied: 'Copiado', pagination: 'Página anterior y siguiente', outline: 'En esta página', user: 'Cómo usar Vezuna', developer: 'Guía para desarrolladores' },
} as const;
