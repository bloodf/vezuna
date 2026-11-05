export const locales = ['en', 'pt-br', 'es'] as const;
export type Locale = (typeof locales)[number];

export const htmlLang: Record<Locale, string> = {
  en: 'en',
  'pt-br': 'pt-BR',
  es: 'es',
};

export const languageNames: Record<Locale, string> = {
  en: 'English',
  'pt-br': 'Português',
  es: 'Español',
};

export function localizedPath(locale: Locale, path = ''): string {
  const clean = path.replace(/^\//, '');
  const prefix = locale === 'en' ? '' : `/${locale}`;
  return `${prefix}${clean ? `/${clean}` : ''}` || '/';
}

export type Alternates = Partial<Record<Locale, string>>;

export const marketingAlternates: Alternates = {
  en: '/',
  'pt-br': '/pt-br',
  es: '/es',
};

export const supportAlternates: Alternates = {
  en: '/support',
  'pt-br': '/pt-br/support',
  es: '/es/support',
};

export const repositoryUrl = 'https://github.com/bloodf/vezuna';
export const issuesUrl = `${repositoryUrl}/issues`;
export const securityUrl = `${repositoryUrl}/security`;
export const contributingUrl = `${repositoryUrl}/blob/main/CONTRIBUTING.md`;
export const noticesUrl = `${repositoryUrl}/blob/main/THIRD_PARTY_NOTICES.md`;
export const licenseUrl = `${repositoryUrl}/blob/main/LICENSE`;
export const developmentGuideUrl = `${repositoryUrl}/blob/main/docs/DEVELOPMENT.md`;

/** Standalone product pages, in the order they appear in the footer. */
export const productPages = ['open-source', 'screens', 'how-it-works', 'local-data'] as const;
export type ProductPage = (typeof productPages)[number];
