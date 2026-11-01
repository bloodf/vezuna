import type { Locale } from '../lib/i18n';

// Product facts on this page are checked against README.md, PRIVACY.md,
// docs/STORE_REVIEW_DRAFT.md and the app's string resources. Sources per claim:
// - Price US$9.99: saved in both stores (docs/RELEASING.md), not yet on sale, so it stays prospective.
// - Android 8.0+ / iOS 18.5+: README.md requirements.
// - Passphrase backups (12+ characters, no recovery): shared/.../values/strings.xml backup_*.
// - App lock (biometrics or passcode, 5 minutes, screenshots blocked): strings.xml app_lock_*.
// - AI: production model catalog is empty, so the page promises no AI feature.

export interface Screenshot { src: string; alt: string; caption: string }

export interface MarketingCopy {
  meta: { title: string; description: string };
  nav: { how: string; records: string; privacy: string; price: string; faq: string; docs: string; menu: string; home: string; product: string };
  hero: {
    title: string; body: string; primary: string; secondary: string;
    facts: { term: string; value: string }[];
    screen: Screenshot;
  };
  how: { title: string; body: string; steps: { title: string; body: string }[]; screen: Screenshot };
  records: { title: string; body: string; items: { term: string; body: string }[]; screen: Screenshot };
  privacy: {
    title: string; body: string;
    columns: { what: string; where: string; leaves: string };
    rows: { what: string; where: string; leaves: string }[];
    note: string; policy: string; details: string;
  };
  price: { amount: string; title: string; body: string; status: string; primary: string; secondary: string };
  faq: { title: string; items: { question: string; answer: string }[] };
  footer: { tagline: string; docs: string; support: string; privacy: string; source: string; notices: string; medical: string; licenses: string };
}

const shots = {
  home: '/assets/android-home',
  labs: '/assets/android-labs',
  settings: '/assets/android-settings',
};

export const marketingCopy: Record<Locale, MarketingCopy> = {
  en: {
    meta: {
      title: 'Vezuna: health records for your household, kept on your phone',
      description: 'Lab results, medication reminders, and health data for each person in your household, encrypted on your phone. No account, no app server. Android and iPhone.',
    },
    nav: { how: 'How it works', records: 'What it keeps', privacy: 'Privacy', price: 'Price', faq: 'Questions', docs: 'Documentation', menu: 'Menu', home: 'Vezuna home', product: 'Product' },
    hero: {
      title: 'Keep your household’s health records on your phone.',
      body: 'Import lab reports, check every value before it is saved, and get medication reminders for each person you care for. Records are encrypted on the phone. There is no account and no Vezuna server.',
      primary: 'Read the getting-started guide', secondary: 'Price and availability',
      facts: [
        { term: 'Price', value: 'US$9.99 at launch' },
        { term: 'Phones', value: 'Android 8.0+ and iPhone on iOS 18.5+' },
        { term: 'Languages', value: 'English, Português, Español' },
      ],
      screen: { src: shots.home, alt: 'Vezuna home screen for a profile named Child, showing “Your health, in one place.”, a 100% on-device indicator, and an empty medication list.', caption: 'Home screen. Android development build, fictional profile.' },
    },
    how: {
      title: 'A lab report becomes a record only after you check it.',
      body: 'Text recognition makes mistakes. Vezuna treats what it reads as a draft and waits for you.',
      steps: [
        { title: 'Photograph or import the report', body: 'Use the camera, a saved image, or a PDF. Text recognition runs on the phone.' },
        { title: 'Check the draft against the paper', body: 'Vezuna shows the test, value, unit, reference range, and date it read. Fix anything that does not match the original.' },
        { title: 'Confirm, then compare', body: 'Confirmed results join the person’s history. Results are graphed together only when their units match.' },
      ],
      screen: { src: shots.labs, alt: 'Vezuna Lab records screen with Import review and Trends buttons and the empty state “No confirmed results”.', caption: 'Lab records before the first confirmed result.' },
    },
    records: {
      title: 'What Vezuna keeps for each person.',
      body: 'Every person in the household gets a separate profile. Records never mix between profiles.',
      items: [
        { term: 'Lab results', body: 'Confirmed values with the reference range printed on the original report, and trends over time.' },
        { term: 'Medications', body: 'Schedules and reminders. Mark each dose as taken, skipped, or snoozed.' },
        { term: 'Weight, heart rate, steps', body: 'Type them in, or allow read-only import from Health Connect or Apple Health. Both are optional.' },
        { term: 'Exports', body: 'A PDF or CSV of confirmed results for an appointment, sent through your phone’s share sheet.' },
        { term: 'Backups', body: 'An encrypted file protected by a passphrase you choose. Restore it on a new phone.' },
        { term: 'App lock', body: 'Ask for biometrics or the device passcode when Vezuna opens. Screenshots are blocked while it is on.' },
      ],
      screen: { src: shots.settings, alt: 'Vezuna Settings and export screen stating “No Vezuna app server”, with Prepare CSV and Prepare PDF buttons.', caption: 'Settings and export. Exports never depend on an AI model.' },
    },
    privacy: {
      title: 'Where your records stay, and when they leave.',
      body: 'Vezuna has no server and no account. The phone is the only place your records exist until you decide otherwise.',
      columns: { what: 'Data', where: 'Where it stays', leaves: 'When it leaves the phone' },
      rows: [
        { what: 'Health records', where: 'An encrypted vault on the phone, separated by profile', leaves: 'Only when you export or back up' },
        { what: 'PDF and CSV exports', where: 'A plain file you create on request', leaves: 'When you share or save it. From then on it is outside the vault' },
        { what: 'Backups', where: 'A file encrypted with your passphrase', leaves: 'Wherever you save it. Without the passphrase nobody can open it, including us' },
        { what: 'Health Connect and Apple Health', where: 'Read with your permission, never written', leaves: 'Never sent anywhere' },
        { what: 'Purchase', where: 'Handled by the App Store or Google Play', leaves: 'The store sees the purchase, never your records' },
      ],
      note: 'Android encrypts the vault with AES-256-GCM using a key in Android Keystore. iPhone uses CryptoKit AES-GCM. No independent security audit has been done yet.',
      policy: 'Privacy policy', details: 'Technical privacy details',
    },
    price: {
      amount: 'US$9.99',
      title: 'One price in the App Store and Google Play.',
      body: 'Vezuna is a paid app with no ads. The price is set in both stores, and the app goes on sale once store review is complete.',
      status: 'Not available to download yet. No stable release has been published.',
      primary: 'Read the getting-started guide', secondary: 'Follow the source on GitHub',
    },
    faq: {
      title: 'Questions',
      items: [
        { question: 'When can I download it?', answer: 'After App Store and Google Play review. Both listings are being prepared, and there are no download links until review is complete.' },
        { question: 'Which phones does it run on?', answer: 'Android 8.0 or later, and iPhone with iOS 18.5 or later. There is no Mac or desktop version.' },
        { question: 'Does Vezuna give medical advice?', answer: 'No. It keeps records. It does not diagnose, recommend treatment, or change doses. Check imported values against the original report and talk to a qualified professional.' },
        { question: 'What happens if I lose my phone?', answer: 'Your records exist only on that phone. If you made a backup and remember its passphrase, restore it on the new phone. Without a backup, the records cannot be recovered.' },
        { question: 'Does it use AI?', answer: 'This version ships no AI model, so no AI features are available. Records, exports, and backups never depend on one.' },
        { question: 'Which permissions does it ask for?', answer: 'Camera and photos for importing reports, notifications for reminders, and read access to Health Connect or Apple Health. All are optional. Manual records keep working if you say no.' },
        { question: 'Which languages are included?', answer: 'English, Brazilian Portuguese, and Spanish. The app follows your phone’s language.' },
      ],
    },
    footer: {
      tagline: 'Your health, in one place.', docs: 'Documentation', support: 'Support', privacy: 'Privacy policy', source: 'Source code', notices: 'Third-party notices',
      medical: 'Vezuna organizes personal health records. It does not replace advice from a qualified healthcare professional.',
      licenses: 'Source and original artwork under MIT. Noto Sans under SIL OFL 1.1.',
    },
  },
  'pt-br': {
    meta: {
      title: 'Vezuna: registros de saúde da família, guardados no seu celular',
      description: 'Exames, lembretes de medicamentos e dados de saúde de cada pessoa da família, criptografados no seu celular. Sem conta e sem servidor. Android e iPhone.',
    },
    nav: { how: 'Como funciona', records: 'O que guarda', privacy: 'Privacidade', price: 'Preço', faq: 'Perguntas', docs: 'Documentação', menu: 'Menu', home: 'Início do Vezuna', product: 'Produto' },
    hero: {
      title: 'Guarde os registros de saúde da família no seu celular.',
      body: 'Importe exames, confira cada valor antes de salvar e receba lembretes de medicamentos para cada pessoa de quem você cuida. Os registros ficam criptografados no celular. Não tem conta nem servidor do Vezuna.',
      primary: 'Ler o guia de primeiros passos', secondary: 'Preço e disponibilidade',
      facts: [
        { term: 'Preço', value: 'US$9,99 no lançamento' },
        { term: 'Celulares', value: 'Android 8.0+ e iPhone com iOS 18.5+' },
        { term: 'Idiomas', value: 'English, Português, Español' },
      ],
      screen: { src: shots.home, alt: 'Tela inicial do Vezuna para o perfil Child, com “Your health, in one place.”, indicador 100% on-device e lista de medicamentos vazia.', caption: 'Tela inicial. Build de desenvolvimento para Android, perfil fictício, interface em inglês.' },
    },
    how: {
      title: 'Um exame só vira registro depois que você confere.',
      body: 'O reconhecimento de texto erra. Por isso o Vezuna trata o que leu como rascunho e espera você.',
      steps: [
        { title: 'Fotografe ou importe o laudo', body: 'Use a câmera, uma imagem salva ou um PDF. O reconhecimento de texto roda no celular.' },
        { title: 'Confira o rascunho com o papel', body: 'O Vezuna mostra o exame, o valor, a unidade, o intervalo de referência e a data que leu. Corrija o que estiver diferente do original.' },
        { title: 'Confirme e compare', body: 'Resultados confirmados entram no histórico da pessoa. O gráfico só junta resultados com a mesma unidade.' },
      ],
      screen: { src: shots.labs, alt: 'Tela Lab records do Vezuna com os botões Import review e Trends e o aviso “No confirmed results”.', caption: 'Exames antes do primeiro resultado confirmado.' },
    },
    records: {
      title: 'O que o Vezuna guarda para cada pessoa.',
      body: 'Cada pessoa da família tem um perfil separado. Os registros nunca se misturam entre perfis.',
      items: [
        { term: 'Exames', body: 'Valores confirmados com o intervalo de referência impresso no laudo original e a evolução ao longo do tempo.' },
        { term: 'Medicamentos', body: 'Horários e lembretes. Marque cada dose como tomada, pulada ou adiada.' },
        { term: 'Peso, frequência cardíaca, passos', body: 'Digite à mão ou permita importar, só leitura, do Health Connect ou do Apple Saúde. As duas opções são opcionais.' },
        { term: 'Exportações', body: 'Um PDF ou CSV dos resultados confirmados para levar à consulta, enviado pelo compartilhamento do celular.' },
        { term: 'Backups', body: 'Um arquivo criptografado com uma frase-senha que você escolhe. Restaure num celular novo.' },
        { term: 'Bloqueio do app', body: 'Peça biometria ou a senha do aparelho quando o Vezuna abrir. Capturas de tela ficam bloqueadas enquanto estiver ligado.' },
      ],
      screen: { src: shots.settings, alt: 'Tela Settings and export do Vezuna com o aviso “No Vezuna app server” e os botões Prepare CSV e Prepare PDF.', caption: 'Configurações e exportação. Exportar nunca depende de modelo de IA.' },
    },
    privacy: {
      title: 'Onde seus registros ficam, e quando saem.',
      body: 'O Vezuna não tem servidor nem conta. O celular é o único lugar onde seus registros existem até você decidir outra coisa.',
      columns: { what: 'Dado', where: 'Onde fica', leaves: 'Quando sai do celular' },
      rows: [
        { what: 'Registros de saúde', where: 'Num cofre criptografado no celular, separado por perfil', leaves: 'Só quando você exporta ou faz backup' },
        { what: 'Exportações em PDF e CSV', where: 'Um arquivo comum que você cria quando pede', leaves: 'Quando você compartilha ou salva. A partir daí, fica fora do cofre' },
        { what: 'Backups', where: 'Um arquivo criptografado com a sua frase-senha', leaves: 'Onde você salvar. Sem a frase-senha ninguém abre, nem a gente' },
        { what: 'Health Connect e Apple Saúde', where: 'Lidos com a sua permissão, nunca alterados', leaves: 'Nunca são enviados' },
        { what: 'Compra', where: 'Feita pela App Store ou pelo Google Play', leaves: 'A loja vê a compra, nunca os registros' },
      ],
      note: 'No Android, o cofre usa AES-256-GCM com chave no Android Keystore. No iPhone, CryptoKit AES-GCM. Ainda não houve auditoria de segurança independente.',
      policy: 'Política de privacidade', details: 'Detalhes técnicos de privacidade (em inglês)',
    },
    price: {
      amount: 'US$9,99',
      title: 'Um preço só, na App Store e no Google Play.',
      body: 'O Vezuna é um app pago, sem anúncios. O preço já está definido nas duas lojas, e as vendas começam quando a análise das lojas terminar.',
      status: 'Ainda não dá para baixar. Nenhuma versão estável foi publicada.',
      primary: 'Ler o guia de primeiros passos', secondary: 'Acompanhar o código no GitHub',
    },
    faq: {
      title: 'Perguntas',
      items: [
        { question: 'Quando vou poder baixar?', answer: 'Depois da análise da App Store e do Google Play. As duas páginas estão em preparação, e não há link de download até a análise terminar.' },
        { question: 'Em quais celulares funciona?', answer: 'Android 8.0 ou posterior e iPhone com iOS 18.5 ou posterior. Não existe versão para Mac ou computador.' },
        { question: 'O Vezuna dá orientação médica?', answer: 'Não. Ele guarda registros. Não diagnostica, não recomenda tratamento e não muda doses. Confira os valores importados com o laudo original e fale com um profissional de saúde.' },
        { question: 'E se eu perder o celular?', answer: 'Seus registros existem só naquele celular. Se você fez um backup e lembra a frase-senha, restaure no celular novo. Sem backup, os registros não podem ser recuperados.' },
        { question: 'Usa inteligência artificial?', answer: 'Esta versão não traz modelo de IA, então nenhum recurso de IA está disponível. Registros, exportações e backups nunca dependem de um.' },
        { question: 'Quais permissões ele pede?', answer: 'Câmera e fotos para importar laudos, notificações para lembretes e leitura do Health Connect ou do Apple Saúde. Todas são opcionais. Os registros manuais continuam funcionando se você recusar.' },
        { question: 'Quais idiomas estão incluídos?', answer: 'Inglês, português do Brasil e espanhol. O app segue o idioma do celular.' },
      ],
    },
    footer: {
      tagline: 'Sua saúde, em um só lugar.', docs: 'Documentação', support: 'Suporte', privacy: 'Política de privacidade', source: 'Código-fonte', notices: 'Avisos de terceiros',
      medical: 'O Vezuna organiza registros pessoais de saúde. Ele não substitui a orientação de um profissional de saúde qualificado.',
      licenses: 'Código e arte original sob MIT. Noto Sans sob SIL OFL 1.1.',
    },
  },
  es: {
    meta: {
      title: 'Vezuna: registros de salud de tu familia, guardados en tu teléfono',
      description: 'Análisis, recordatorios de medicamentos y datos de salud de cada persona de tu familia, cifrados en tu teléfono. Sin cuenta y sin servidor. Android y iPhone.',
    },
    nav: { how: 'Cómo funciona', records: 'Qué guarda', privacy: 'Privacidad', price: 'Precio', faq: 'Preguntas', docs: 'Documentación', menu: 'Menú', home: 'Inicio de Vezuna', product: 'Producto' },
    hero: {
      title: 'Guarda los registros de salud de tu familia en tu teléfono.',
      body: 'Importa análisis, revisa cada valor antes de guardarlo y recibe recordatorios de medicamentos para cada persona que cuidas. Los registros se cifran en el teléfono. No hay cuenta ni servidor de Vezuna.',
      primary: 'Leer la guía de inicio', secondary: 'Precio y disponibilidad',
      facts: [
        { term: 'Precio', value: 'US$9,99 en el lanzamiento' },
        { term: 'Teléfonos', value: 'Android 8.0+ y iPhone con iOS 18.5+' },
        { term: 'Idiomas', value: 'English, Português, Español' },
      ],
      screen: { src: shots.home, alt: 'Pantalla de inicio de Vezuna para el perfil Child, con “Your health, in one place.”, un indicador 100% on-device y la lista de medicamentos vacía.', caption: 'Pantalla de inicio. Build de desarrollo para Android, perfil ficticio, interfaz en inglés.' },
    },
    how: {
      title: 'Un análisis se vuelve registro solo cuando tú lo revisas.',
      body: 'El reconocimiento de texto se equivoca. Vezuna trata lo que lee como un borrador y te espera.',
      steps: [
        { title: 'Fotografía o importa el informe', body: 'Usa la cámara, una imagen guardada o un PDF. El reconocimiento de texto se ejecuta en el teléfono.' },
        { title: 'Compara el borrador con el papel', body: 'Vezuna muestra la prueba, el valor, la unidad, el rango de referencia y la fecha que leyó. Corrige lo que no coincida con el original.' },
        { title: 'Confirma y compara', body: 'Los resultados confirmados entran en el historial de la persona. Solo se grafican juntos los resultados con la misma unidad.' },
      ],
      screen: { src: shots.labs, alt: 'Pantalla Lab records de Vezuna con los botones Import review y Trends y el aviso “No confirmed results”.', caption: 'Análisis antes del primer resultado confirmado.' },
    },
    records: {
      title: 'Lo que Vezuna guarda para cada persona.',
      body: 'Cada persona de la familia tiene un perfil separado. Los registros nunca se mezclan entre perfiles.',
      items: [
        { term: 'Análisis', body: 'Valores confirmados con el rango de referencia impreso en el informe original y su evolución en el tiempo.' },
        { term: 'Medicamentos', body: 'Horarios y recordatorios. Marca cada dosis como tomada, omitida o pospuesta.' },
        { term: 'Peso, frecuencia cardíaca, pasos', body: 'Escríbelos a mano o permite importarlos, solo lectura, desde Health Connect o Apple Salud. Ambas opciones son opcionales.' },
        { term: 'Exportaciones', body: 'Un PDF o CSV de los resultados confirmados para la consulta, enviado con el menú de compartir del teléfono.' },
        { term: 'Copias de seguridad', body: 'Un archivo cifrado con una frase de contraseña que tú eliges. Restáuralo en un teléfono nuevo.' },
        { term: 'Bloqueo de la app', body: 'Pide biometría o el código del dispositivo cuando Vezuna se abre. Las capturas de pantalla se bloquean mientras está activo.' },
      ],
      screen: { src: shots.settings, alt: 'Pantalla Settings and export de Vezuna con el aviso “No Vezuna app server” y los botones Prepare CSV y Prepare PDF.', caption: 'Ajustes y exportación. Exportar nunca depende de un modelo de IA.' },
    },
    privacy: {
      title: 'Dónde se quedan tus registros, y cuándo salen.',
      body: 'Vezuna no tiene servidor ni cuenta. El teléfono es el único lugar donde existen tus registros hasta que tú decidas otra cosa.',
      columns: { what: 'Dato', where: 'Dónde se queda', leaves: 'Cuándo sale del teléfono' },
      rows: [
        { what: 'Registros de salud', where: 'En un almacén cifrado en el teléfono, separado por perfil', leaves: 'Solo cuando exportas o haces una copia de seguridad' },
        { what: 'Exportaciones PDF y CSV', where: 'Un archivo normal que creas cuando lo pides', leaves: 'Cuando lo compartes o lo guardas. Desde entonces está fuera del almacén' },
        { what: 'Copias de seguridad', where: 'Un archivo cifrado con tu frase de contraseña', leaves: 'Donde lo guardes. Sin la frase nadie puede abrirlo, ni nosotros' },
        { what: 'Health Connect y Apple Salud', where: 'Se leen con tu permiso, nunca se modifican', leaves: 'Nunca se envían' },
        { what: 'Compra', where: 'La gestiona App Store o Google Play', leaves: 'La tienda ve la compra, nunca tus registros' },
      ],
      note: 'En Android, el almacén usa AES-256-GCM con una clave en Android Keystore. En iPhone, CryptoKit AES-GCM. Todavía no hay una auditoría de seguridad independiente.',
      policy: 'Política de privacidad', details: 'Detalles técnicos de privacidad (en inglés)',
    },
    price: {
      amount: 'US$9,99',
      title: 'Un solo precio en App Store y Google Play.',
      body: 'Vezuna es una app de pago, sin anuncios. El precio ya está fijado en ambas tiendas y la venta empieza cuando termine la revisión.',
      status: 'Todavía no se puede descargar. No se ha publicado una versión estable.',
      primary: 'Leer la guía de inicio', secondary: 'Seguir el código en GitHub',
    },
    faq: {
      title: 'Preguntas',
      items: [
        { question: '¿Cuándo podré descargarla?', answer: 'Después de la revisión de App Store y Google Play. Ambas fichas están en preparación y no hay enlaces de descarga hasta que termine la revisión.' },
        { question: '¿En qué teléfonos funciona?', answer: 'Android 8.0 o posterior, y iPhone con iOS 18.5 o posterior. No hay versión para Mac ni para computadora.' },
        { question: '¿Vezuna da consejos médicos?', answer: 'No. Guarda registros. No diagnostica, no recomienda tratamientos y no cambia dosis. Revisa los valores importados con el informe original y consulta a un profesional.' },
        { question: '¿Qué pasa si pierdo el teléfono?', answer: 'Tus registros existen solo en ese teléfono. Si hiciste una copia de seguridad y recuerdas su frase de contraseña, restáurala en el teléfono nuevo. Sin copia, los registros no se pueden recuperar.' },
        { question: '¿Usa inteligencia artificial?', answer: 'Esta versión no incluye ningún modelo de IA, así que no hay funciones de IA disponibles. Los registros, las exportaciones y las copias nunca dependen de uno.' },
        { question: '¿Qué permisos pide?', answer: 'Cámara y fotos para importar informes, notificaciones para los recordatorios y lectura de Health Connect o Apple Salud. Todos son opcionales. Los registros manuales siguen funcionando si los rechazas.' },
        { question: '¿Qué idiomas incluye?', answer: 'Inglés, portugués de Brasil y español. La app sigue el idioma del teléfono.' },
      ],
    },
    footer: {
      tagline: 'Tu salud, en un solo lugar.', docs: 'Documentación', support: 'Soporte', privacy: 'Política de privacidad', source: 'Código fuente', notices: 'Avisos de terceros',
      medical: 'Vezuna organiza registros personales de salud. No sustituye el consejo de un profesional de la salud calificado.',
      licenses: 'Código y arte original bajo MIT. Noto Sans bajo SIL OFL 1.1.',
    },
  },
};

export interface SupportCopy {
  title: string; intro: string;
  emailTitle: string; emailBody: string;
  generalTitle: string; generalBody: string; action: string;
  securityTitle: string; securityBody: string; securityAction: string; fallback: string;
  warning: string;
}

export const supportCopy: Record<Locale, SupportCopy> = {
  en: {
    title: 'Get help without sharing your records.',
    intro: 'Support is run by the developer on a best-effort basis. There is no response-time commitment.',
    emailTitle: 'Email', emailBody: 'For questions about the app, purchases, or privacy.',
    generalTitle: 'Questions and bug reports', generalBody: 'Open a GitHub issue. Include the platform, the app version or commit, and the steps that reproduce the problem.', action: 'Open GitHub issues',
    securityTitle: 'Security reports', securityBody: 'Open the repository’s Security tab and choose “Report a vulnerability”. Include the platform, version or commit, reproduction steps, impact, and a suggested fix if you have one. This uses GitHub private vulnerability reporting.', securityAction: 'Open the Security tab',
    fallback: 'If private reporting is unavailable, open a public issue only to ask for a private channel. Leave out vulnerability details and exploit steps.',
    warning: 'Use synthetic examples only. Never attach actual medical documents, screenshots of personal records, vault files, or backups to any support channel.',
  },
  'pt-br': {
    title: 'Peça ajuda sem compartilhar seus registros.',
    intro: 'O suporte é feito pelo desenvolvedor, com melhor esforço. Não há compromisso de tempo de resposta.',
    emailTitle: 'E-mail', emailBody: 'Para dúvidas sobre o app, compras ou privacidade.',
    generalTitle: 'Dúvidas e problemas', generalBody: 'Abra uma issue no GitHub. Informe a plataforma, a versão do app ou o commit e os passos que reproduzem o problema.', action: 'Abrir issues no GitHub',
    securityTitle: 'Relatórios de segurança', securityBody: 'Na aba Security do repositório, escolha “Report a vulnerability”. Informe plataforma, versão ou commit, passos de reprodução, impacto e uma correção sugerida, se houver. O canal é o relatório privado de vulnerabilidades do GitHub.', securityAction: 'Abrir a aba Security',
    fallback: 'Se o canal privado não estiver disponível, abra uma issue pública só para pedir um canal privado. Deixe de fora detalhes da vulnerabilidade e passos de exploração.',
    warning: 'Use apenas exemplos fictícios. Nunca anexe documentos médicos de pessoas, capturas de registros pessoais, arquivos do cofre ou backups em nenhum canal de suporte.',
  },
  es: {
    title: 'Recibe ayuda sin compartir tus registros.',
    intro: 'El soporte lo atiende el desarrollador con el mejor esfuerzo. No hay compromiso de tiempo de respuesta.',
    emailTitle: 'Correo', emailBody: 'Para preguntas sobre la app, compras o privacidad.',
    generalTitle: 'Preguntas y errores', generalBody: 'Abre una issue en GitHub. Indica la plataforma, la versión de la app o el commit y los pasos que reproducen el problema.', action: 'Abrir issues en GitHub',
    securityTitle: 'Informes de seguridad', securityBody: 'En la pestaña Security del repositorio, elige “Report a vulnerability”. Indica plataforma, versión o commit, pasos de reproducción, impacto y una corrección sugerida si la tienes. El canal es el informe privado de vulnerabilidades de GitHub.', securityAction: 'Abrir la pestaña Security',
    fallback: 'Si el canal privado no está disponible, abre una issue pública solo para pedir un canal privado. Deja fuera los detalles de la vulnerabilidad y los pasos de explotación.',
    warning: 'Usa solo ejemplos ficticios. Nunca adjuntes documentos médicos reales, capturas de registros personales, archivos del almacenamiento ni copias de seguridad en ningún canal de soporte.',
  },
};
