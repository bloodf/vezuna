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
