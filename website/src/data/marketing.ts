import type { Locale } from '../lib/i18n';

export interface MarketingCopy {
  nav: { story: string; routines: string; privacy: string; faq: string; docs: string; menu: string };
  hero: {
    eyebrow: string; title: string; body: string; primary: string; secondary: string;
    note: string; galleryLabel: string; caption: string;
    screenshots: { src: string; alt: string; label: string }[];
  };
  story: {
    eyebrow: string; title: string; intro: string;
    steps: { title: string; body: string }[];
    confirmation: string;
  };
  routines: {
    eyebrow: string; title: string; intro: string;
    items: { title: string; body: string }[];
    exampleLabel: string; exampleTitle: string; exampleNote: string;
    schedule: { time: string; title: string; detail: string; state: string }[];
  };
  tech: { eyebrow: string; title: string; description: string; draft: string; confirmed: string; comparison: string; local: string; staticNote: string };
  availability: { eyebrow: string; title: string; body: string; github: string; appStore: string; googlePlay: string; pending: string };
  privacy: {
    eyebrow: string; title: string; intro: string;
    items: { title: string; body: string }[];
    boundary: string;
  };
  faq: { eyebrow: string; title: string; items: { question: string; answer: string }[] };
  footer: {
    tagline: string; docs: string; support: string; privacy: string; source: string; notices: string; medical: string;
  };
}

const screenshots = {
  home: '/assets/android-home.png',
  labs: '/assets/android-labs.png',
  settings: '/assets/android-settings.png',
};

export const marketingCopy: Record<Locale, MarketingCopy> = {
  en: {
    nav: { story: 'How it works', routines: 'Medications & exports', privacy: 'Privacy', faq: 'FAQ', docs: 'Read the docs', menu: 'Menu' },
    hero: {
      eyebrow: 'Household health records, kept on your device.',
      title: 'Your health, in one place.',
      body: 'Bring lab reports, medication schedules, and household health records together. Check imported details, follow confirmed results, and keep everything organized on your device.',
      primary: 'Read the docs', secondary: 'View source',
      note: 'Release preparation: no stable release has been published. Physical-device checks and store review are still pending.',
      galleryLabel: 'Vezuna Android app previews',
      caption: 'Actual Android development-build screens with synthetic example records. iPhone runtime and store-distribution checks remain pending.',
      screenshots: [
        { src: screenshots.home, alt: 'Vezuna household dashboard for the fictional Child profile', label: 'Household overview' },
        { src: screenshots.labs, alt: 'Vezuna laboratory records with import review and trends controls', label: 'Laboratory records' },
        { src: screenshots.settings, alt: 'Vezuna settings showing the offline boundary and PDF and CSV export actions', label: 'Privacy and exports' },
      ],
    },
    story: {
      eyebrow: 'A record you can check',
      title: 'From report to a clearer record.',
      intro: 'Keep each person’s information separate, then move from an imported document to confirmed results at your own pace.',
      steps: [
        { title: 'Create separate profiles', body: 'Add a profile for each person in your household and keep their records separate.' },
        { title: 'Import images and PDFs', body: 'Bring a lab report into Vezuna from a camera capture, an image, or a PDF. Embedded text and OCR are processed on the device.' },
        { title: 'Review before confirming', body: 'Check the OCR draft against the original report. Correct the test name, value, unit, reference range, or date before you confirm it.' },
        { title: 'Compare confirmed results', body: 'See confirmed results side by side and follow changes over time, preserving the original values and report-specific references.' },
      ],
      confirmation: 'Imported results stay unconfirmed until you review them. Only confirmed results appear in trends and clinician exports.',
    },
    routines: {
      eyebrow: 'Daily routines',
      title: 'Routines and records, ready when you need them.',
      intro: 'Keep schedules beside the records they relate to, then prepare a copy for a conversation with your clinician.',
      items: [
        { title: 'Medications', body: 'Keep medication names, recorded doses, and schedules in one place. Vezuna records what you enter; it never chooses or changes a dose.' },
        { title: 'Local reminders', body: 'Use reminders on your device and mark doses as taken, skipped, or snoozed. Actual notification delivery remains a physical-device release check.' },
        { title: 'PDF and CSV exports', body: 'Create PDF reports with result tables and charts, or export confirmed data as CSV. Share a copy through the system share sheet when you choose.' },
      ],
      exampleLabel: 'Synthetic daily example',
      exampleTitle: 'A quiet view of the day',
      exampleNote: 'This illustrative schedule is not a product screenshot or dosing guidance. Names and records are fictional.',
      schedule: [
        { time: '08:00', title: 'Morning routine', detail: 'Demo Medicine · recorded schedule', state: 'Taken' },
        { time: '13:30', title: 'Check the record', detail: 'Review a synthetic imported result', state: 'To review' },
        { time: '18:00', title: 'Prepare for a visit', detail: 'Export confirmed information as PDF', state: 'When needed' },
      ],
    },
    tech: {
      eyebrow: 'Local records, in space',
      title: 'See how records connect.',
      description: 'An abstract spatial view of local health records, from OCR drafts awaiting confirmation to confirmed-result comparisons. Processing stays on your device.',
      draft: 'OCR draft · confirmation required',
      confirmed: 'Confirmed result',
      comparison: 'Compare confirmed results',
      local: 'Processed locally',
      staticNote: 'Abstract visualization with synthetic data, not a product screenshot or medical analysis.',
    },
    availability: {
      eyebrow: 'Release status',
      title: 'Follow development on GitHub.',
      body: 'Vezuna is in release preparation. Explore the source and project updates on GitHub; app store releases are not yet available.',
      github: 'View on GitHub',
      appStore: 'App Store · unavailable',
      googlePlay: 'Google Play · unavailable',
      pending: 'Store availability pending',
    },
    privacy: {
      eyebrow: 'A clear boundary',
      title: 'Your records, on your device. Shared only when you choose.',
      intro: 'Vezuna is built around local records, not an account or an application server.',
      items: [
        { title: 'Local storage', body: 'Health records are stored in an encrypted, profile-isolated vault on your device. No independent security audit is claimed.' },
        { title: 'Optional health imports', body: 'With permission, read heart rate, steps, and weight from Health Connect or HealthKit. Denying access does not block manual records.' },
        { title: 'Platform services', body: 'Store services handle licensing and planned model delivery. Their privacy behavior is separate; final SDK and release checks remain open.' },
      ],
      boundary: 'PDF and CSV exports are plaintext. Once you share an export or backup, its destination and protection are outside Vezuna’s vault.',
    },
    faq: {
      eyebrow: 'Questions, answered plainly', title: 'Before you begin.',
      items: [
        { question: 'When will Vezuna be available?', answer: 'Vezuna is still in release preparation, and no stable release has been published. Store availability will be confirmed only after release checks and store review are complete.' },
        { question: 'Which devices are supported?', answer: 'Vezuna is a native app for Android and iPhone. Android 8.0 or later is the current minimum in source; iPhone requires iOS 18.5 or later. Physical-device release checks are still pending.' },
        { question: 'Which languages are available?', answer: 'English, Brazilian Portuguese, and Spanish across the app and this website’s essential guidance.' },
        { question: 'Which permissions does Vezuna request?', answer: 'Health imports are optional. With permission, Vezuna can read heart rate, steps, and weight from Health Connect or HealthKit. Camera, selected images, and notifications support their respective import and reminder workflows. You can deny health access and still keep manual records.' },
        { question: 'How do backups work?', answer: 'Backups created in the app are encrypted for restoration with the same device key. They do not provide portable recovery to another device or app, and should not be treated as protection against device loss.' },
        { question: 'Does Vezuna provide medical guidance?', answer: 'No. Vezuna organizes the records you provide. It does not diagnose conditions, recommend treatment, or change medication doses. Review OCR drafts and confirm imported values against the original report.' },
      ],
    },
    footer: {
      tagline: 'Household health records, kept on your device.', docs: 'Documentation', support: 'Support', privacy: 'Privacy', source: 'Source code', notices: 'Third-party notices',
      medical: 'Vezuna helps organize personal health records and does not replace advice from a qualified healthcare professional.',
    },
  },
  'pt-br': {
    nav: { story: 'Como funciona', routines: 'Medicamentos e exportações', privacy: 'Privacidade', faq: 'Perguntas frequentes', docs: 'Ler a documentação', menu: 'Menu' },
    hero: {
      eyebrow: 'Registros de saúde da família, guardados no seu dispositivo.',
      title: 'Sua saúde, em um só lugar.',
      body: 'Reúna resultados de exames, horários de medicamentos e registros de saúde da família. Confira os dados importados, acompanhe resultados confirmados e mantenha tudo organizado no seu dispositivo.',
      primary: 'Ler a documentação', secondary: 'Ver o código-fonte',
      note: 'Preparação para lançamento: ainda não há uma versão estável publicada. Os testes em dispositivos físicos e a análise das lojas continuam pendentes.',
      galleryLabel: 'Pré-visualizações do app Vezuna para Android',
      caption: 'Telas reais do build de desenvolvimento para Android com registros fictícios. Os testes de execução no iPhone e de distribuição nas lojas continuam pendentes.',
      screenshots: [
        { src: screenshots.home, alt: 'Painel familiar do Vezuna para o perfil fictício Child', label: 'Visão geral da família' },
        { src: screenshots.labs, alt: 'Registros laboratoriais do Vezuna com controles de revisão de importação e tendências', label: 'Registros de exames' },
        { src: screenshots.settings, alt: 'Configurações do Vezuna mostrando o limite offline e ações de exportação em PDF e CSV', label: 'Privacidade e exportações' },
      ],
    },
    story: {
      eyebrow: 'Um registro que você pode conferir',
      title: 'Do relatório a um registro mais claro.',
      intro: 'Mantenha as informações de cada pessoa separadas e avance do documento importado aos resultados confirmados no seu ritmo.',
      steps: [
        { title: 'Crie perfis separados', body: 'Adicione um perfil para cada pessoa da família e mantenha os registros separados.' },
        { title: 'Importe imagens e PDFs', body: 'Traga um relatório para o Vezuna pela câmera, por uma imagem ou PDF. O texto incorporado e o OCR são processados no dispositivo.' },
        { title: 'Revise antes de confirmar', body: 'Confira o rascunho de OCR com o relatório original. Corrija nome, valor, unidade, intervalo de referência ou data antes de confirmar.' },
        { title: 'Compare resultados confirmados', body: 'Veja resultados confirmados lado a lado e acompanhe mudanças ao longo do tempo, preservando valores originais e referências de cada relatório.' },
      ],
      confirmation: 'Os resultados importados continuam não confirmados até a sua revisão. Somente resultados confirmados aparecem nas tendências e exportações para o profissional de saúde.',
    },
    routines: {
      eyebrow: 'Rotinas diárias',
      title: 'Rotinas e registros, prontos quando você precisar.',
      intro: 'Mantenha os horários junto dos registros relacionados e prepare uma cópia para conversar com seu profissional de saúde.',
      items: [
        { title: 'Medicamentos', body: 'Guarde nomes, doses registradas e horários em um só lugar. O Vezuna registra o que você informa; ele nunca escolhe nem altera uma dose.' },
        { title: 'Lembretes locais', body: 'Use lembretes no dispositivo e marque as doses como tomadas, puladas ou adiadas. A entrega real das notificações ainda precisa ser verificada em dispositivos físicos.' },
        { title: 'Exportações em PDF e CSV', body: 'Crie relatórios em PDF com tabelas e gráficos ou exporte dados confirmados em CSV. Compartilhe uma cópia pelo menu do sistema quando quiser.' },
      ],
      exampleLabel: 'Exemplo diário sintético', exampleTitle: 'Uma visão tranquila do dia',
      exampleNote: 'Este cronograma ilustrativo não é uma captura do produto nem uma orientação de dose. Nomes e registros são fictícios.',
      schedule: [
        { time: '08:00', title: 'Rotina da manhã', detail: 'Medicamento Demonstrativo · horário registrado', state: 'Tomado' },
        { time: '13:30', title: 'Confira o registro', detail: 'Revise um resultado importado fictício', state: 'Para revisar' },
        { time: '18:00', title: 'Prepare-se para uma consulta', detail: 'Exporte informações confirmadas em PDF', state: 'Quando precisar' },
      ],
    },
    tech: {
      eyebrow: 'Registros locais, em uma visão espacial',
      title: 'Veja como os registros se conectam.',
      description: 'Uma visualização espacial abstrata dos registros de saúde locais, dos rascunhos de OCR que aguardam confirmação às comparações de resultados confirmados. O processamento fica no seu dispositivo.',
      draft: 'Rascunho de OCR · confirmação necessária',
      confirmed: 'Resultado confirmado',
      comparison: 'Comparar resultados confirmados',
      local: 'Processado localmente',
      staticNote: 'Visualização abstrata com dados sintéticos, não uma captura do produto nem uma análise médica.',
    },
    availability: {
      eyebrow: 'Status de lançamento',
      title: 'Acompanhe o desenvolvimento no GitHub.',
      body: 'O Vezuna está em preparação para lançamento. Explore o código-fonte e as novidades do projeto no GitHub; as versões nas lojas ainda não estão disponíveis.',
      github: 'Ver no GitHub',
      appStore: 'App Store · indisponível',
      googlePlay: 'Google Play · indisponível',
      pending: 'Disponibilidade nas lojas pendente',
    },
    privacy: {
      eyebrow: 'Um limite claro', title: 'Seus registros, no seu dispositivo. Você escolhe quando compartilhar.',
      intro: 'O Vezuna foi criado em torno de registros locais, sem conta de usuário nem servidor do aplicativo.',
      items: [
        { title: 'Armazenamento local', body: 'Os registros ficam em um cofre criptografado no dispositivo, separados por perfil. Não há alegação de auditoria de segurança independente.' },
        { title: 'Importação opcional de dados', body: 'Com permissão, leia frequência cardíaca, passos e peso do Health Connect ou HealthKit. Recusar o acesso não impede os registros manuais.' },
        { title: 'Serviços das plataformas', body: 'As lojas cuidam do licenciamento e da entrega planejada de modelos. Sua privacidade é separada; as verificações finais de SDK e lançamento continuam abertas.' },
      ],
      boundary: 'Os arquivos PDF e CSV são texto simples. Ao compartilhar uma exportação ou backup, o destino e a proteção ficam fora do cofre do Vezuna.',
    },
    faq: {
      eyebrow: 'Perguntas, respostas diretas', title: 'Antes de começar.',
      items: [
        { question: 'Quando o Vezuna estará disponível?', answer: 'O Vezuna ainda está em preparação para lançamento, e nenhuma versão estável foi publicada. A disponibilidade nas lojas só será confirmada após as verificações e a análise das lojas.' },
        { question: 'Quais dispositivos são compatíveis?', answer: 'O Vezuna é um app nativo para Android e iPhone. O Android 8.0 ou posterior é o mínimo atual no código-fonte; no iPhone, é necessário iOS 18.5 ou posterior. Os testes em dispositivos físicos continuam pendentes.' },
        { question: 'Quais idiomas estão disponíveis?', answer: 'Inglês, português do Brasil e espanhol no app e nas orientações essenciais deste site.' },
        { question: 'Quais permissões o Vezuna solicita?', answer: 'A importação de dados de saúde é opcional. Com permissão, o Vezuna lê frequência cardíaca, passos e peso do Health Connect ou HealthKit. Câmera, imagens selecionadas e notificações apoiam os fluxos de importação e lembretes. Você pode recusar o acesso e continuar com registros manuais.' },
        { question: 'Como funcionam os backups?', answer: 'Os backups são criptografados para restauração com a chave do mesmo dispositivo. Não oferecem recuperação para outro dispositivo ou app e não devem ser tratados como proteção contra perda do aparelho.' },
        { question: 'O Vezuna oferece orientação médica?', answer: 'Não. O Vezuna organiza as informações fornecidas. Ele não diagnostica doenças, recomenda tratamentos nem altera doses. Revise os rascunhos de OCR e confirme os valores com o relatório original.' },
      ],
    },
    footer: {
      tagline: 'Registros de saúde da família, guardados no seu dispositivo.', docs: 'Documentação', support: 'Suporte', privacy: 'Privacidade', source: 'Código-fonte', notices: 'Avisos de terceiros',
      medical: 'O Vezuna ajuda a organizar registros pessoais de saúde e não substitui a orientação de um profissional de saúde qualificado.',
    },
  },
  es: {
    nav: { story: 'Cómo funciona', routines: 'Medicamentos y exportaciones', privacy: 'Privacidad', faq: 'Preguntas frecuentes', docs: 'Leer la documentación', menu: 'Menú' },
    hero: {
      eyebrow: 'Los registros de salud de tu familia, en tu dispositivo.',
      title: 'Tu salud, en un solo lugar.',
      body: 'Reúne informes de laboratorio, horarios de medicamentos y registros de salud familiares. Revisa los datos importados, sigue los resultados confirmados y mantén todo organizado en tu dispositivo.',
      primary: 'Leer la documentación', secondary: 'Ver el código fuente',
      note: 'Preparación para el lanzamiento: todavía no se ha publicado una versión estable. Las pruebas en dispositivos físicos y la revisión de las tiendas siguen pendientes.',
      galleryLabel: 'Vistas previas de la app Vezuna para Android',
      caption: 'Pantallas reales de la compilación de desarrollo para Android con registros ficticios. Las pruebas de ejecución en iPhone y distribución en tiendas siguen pendientes.',
      screenshots: [
        { src: screenshots.home, alt: 'Panel familiar de Vezuna para el perfil ficticio Child', label: 'Resumen del hogar' },
        { src: screenshots.labs, alt: 'Registros de laboratorio de Vezuna con controles de revisión de importación y tendencias', label: 'Registros de laboratorio' },
        { src: screenshots.settings, alt: 'Ajustes de Vezuna con el límite sin conexión y acciones de exportación PDF y CSV', label: 'Privacidad y exportaciones' },
      ],
    },
    story: {
      eyebrow: 'Un registro que puedes comprobar', title: 'Del informe a un registro más claro.',
      intro: 'Mantén separada la información de cada persona y avanza del documento importado a los resultados confirmados a tu ritmo.',
      steps: [
        { title: 'Crea perfiles separados', body: 'Añade un perfil para cada persona del hogar y mantén sus registros separados.' },
        { title: 'Importa imágenes y PDF', body: 'Incorpora un informe desde la cámara, una imagen o un PDF. El texto incorporado y el OCR se procesan en el dispositivo.' },
        { title: 'Revisa antes de confirmar', body: 'Compara el borrador de OCR con el informe original. Corrige nombre, valor, unidad, intervalo de referencia o fecha antes de confirmar.' },
        { title: 'Compara resultados confirmados', body: 'Consulta resultados uno junto a otro y sigue sus cambios, conservando los valores originales y las referencias de cada informe.' },
      ],
      confirmation: 'Los resultados importados permanecen sin confirmar hasta que los revises. Solo los resultados confirmados aparecen en tendencias y exportaciones para profesionales.',
    },
    routines: {
      eyebrow: 'Rutinas diarias', title: 'Rutinas y registros, listos cuando los necesites.',
      intro: 'Mantén los horarios junto a los registros relacionados y prepara una copia para hablar con tu profesional de salud.',
      items: [
        { title: 'Medicamentos', body: 'Guarda nombres, dosis registradas y horarios en un solo lugar. Vezuna registra lo que introduces; nunca elige ni modifica una dosis.' },
        { title: 'Recordatorios locales', body: 'Usa recordatorios en tu dispositivo y marca las dosis como tomadas, omitidas o pospuestas. La entrega real de notificaciones aún debe comprobarse en dispositivos físicos.' },
        { title: 'Exportaciones PDF y CSV', body: 'Crea informes PDF con tablas y gráficos o exporta datos confirmados como CSV. Comparte una copia mediante la hoja para compartir cuando quieras.' },
      ],
      exampleLabel: 'Ejemplo diario sintético', exampleTitle: 'Una vista tranquila del día',
      exampleNote: 'Este horario ilustrativo no es una captura del producto ni una recomendación de dosis. Los nombres y registros son ficticios.',
      schedule: [
        { time: '08:00', title: 'Rutina de la mañana', detail: 'Medicamento de demostración · horario registrado', state: 'Tomado' },
        { time: '13:30', title: 'Comprueba el registro', detail: 'Revisa un resultado importado ficticio', state: 'Para revisar' },
        { time: '18:00', title: 'Prepárate para una consulta', detail: 'Exporta información confirmada como PDF', state: 'Cuando lo necesites' },
      ],
    },
    tech: {
      eyebrow: 'Registros locales, en una vista espacial',
      title: 'Descubre cómo se conectan los registros.',
      description: 'Una visualización espacial abstracta de los registros de salud locales, desde los borradores de OCR pendientes de confirmación hasta las comparaciones de resultados confirmados. El procesamiento permanece en tu dispositivo.',
      draft: 'Borrador de OCR · confirmación necesaria',
      confirmed: 'Resultado confirmado',
      comparison: 'Comparar resultados confirmados',
      local: 'Procesado localmente',
      staticNote: 'Visualización abstracta con datos sintéticos, no una captura del producto ni un análisis médico.',
    },
    availability: {
      eyebrow: 'Estado del lanzamiento',
      title: 'Sigue el desarrollo en GitHub.',
      body: 'Vezuna está en preparación para su lanzamiento. Explora el código fuente y las novedades del proyecto en GitHub; las versiones en las tiendas aún no están disponibles.',
      github: 'Ver en GitHub',
      appStore: 'App Store · no disponible',
      googlePlay: 'Google Play · no disponible',
      pending: 'Disponibilidad en tiendas pendiente',
    },
    privacy: {
      eyebrow: 'Un límite claro', title: 'Tus registros, en tu dispositivo. Tú decides cuándo compartirlos.',
      intro: 'Vezuna se basa en registros locales, sin cuentas de usuario ni servidor de aplicación.',
      items: [
        { title: 'Almacenamiento local', body: 'Los registros se guardan en un almacenamiento cifrado y separado por perfil en tu dispositivo. No se afirma que exista una auditoría de seguridad independiente.' },
        { title: 'Importación opcional de datos', body: 'Con permiso, lee frecuencia cardíaca, pasos y peso desde Health Connect o HealthKit. Rechazar el acceso no impide usar registros manuales.' },
        { title: 'Servicios de plataforma', body: 'Las tiendas gestionan licencias y la entrega prevista de modelos. Su privacidad es independiente; las comprobaciones finales de SDK y lanzamiento siguen abiertas.' },
      ],
      boundary: 'Los archivos PDF y CSV no están cifrados. Al compartir una exportación o copia de seguridad, su destino y protección quedan fuera del almacenamiento de Vezuna.',
    },
    faq: {
      eyebrow: 'Preguntas, respuestas claras', title: 'Antes de empezar.',
      items: [
        { question: '¿Cuándo estará disponible Vezuna?', answer: 'Vezuna todavía está en preparación y no se ha publicado una versión estable. La disponibilidad en tiendas solo se confirmará después de las comprobaciones y la revisión de las tiendas.' },
        { question: '¿Qué dispositivos son compatibles?', answer: 'Vezuna es una app nativa para Android y iPhone. Android 8.0 o posterior es el mínimo actual en el código fuente; en iPhone se requiere iOS 18.5 o posterior. Las pruebas físicas siguen pendientes.' },
        { question: '¿Qué idiomas están disponibles?', answer: 'Inglés, portugués de Brasil y español en la app y en las guías esenciales de este sitio.' },
        { question: '¿Qué permisos solicita Vezuna?', answer: 'La importación de datos de salud es opcional. Con permiso, Vezuna lee frecuencia cardíaca, pasos y peso desde Health Connect o HealthKit. Cámara, imágenes seleccionadas y notificaciones apoyan las importaciones y recordatorios. Puedes rechazar el acceso y seguir usando registros manuales.' },
        { question: '¿Cómo funcionan las copias de seguridad?', answer: 'Las copias están cifradas para restaurarse con la clave del mismo dispositivo. No ofrecen recuperación en otro dispositivo o app ni deben considerarse protección contra la pérdida del dispositivo.' },
        { question: '¿Vezuna ofrece orientación médica?', answer: 'No. Vezuna organiza la información que proporcionas. No diagnostica enfermedades, no recomienda tratamientos ni modifica dosis. Revisa los borradores de OCR y confirma los valores con el informe original.' },
      ],
    },
    footer: {
      tagline: 'Los registros de salud de tu familia, en tu dispositivo.', docs: 'Documentación', support: 'Soporte', privacy: 'Privacidad', source: 'Código fuente', notices: 'Avisos de terceros',
      medical: 'Vezuna ayuda a organizar registros personales de salud y no reemplaza la orientación de un profesional de la salud.',
    },
  },
};

export const supportCopy: Record<Locale, { eyebrow: string; title: string; intro: string; generalTitle: string; generalBody: string; action: string; securityTitle: string; securityBody: string; securityAction: string; fallback: string; warning: string }> = {
  en: {
    eyebrow: 'Best-effort project support', title: 'Get help without sharing your records.',
    intro: 'Use the project’s public issue tracker for general questions and GitHub private vulnerability reporting for security issues. No support email or response-time commitment is currently provided.',
    generalTitle: 'Questions and bug reports', generalBody: 'For product questions, privacy questions, or bug reports, open a GitHub issue. Include the affected platform and app version or commit, plus reproduction steps where useful.', action: 'Open GitHub issues',
    securityTitle: 'Security reports', securityBody: 'Open the repository’s Security tab and choose “Report a vulnerability”. Include the affected platform, version or commit, reproduction steps, impact, and a suggested fix if available.', securityAction: 'Open the Security tab',
    fallback: 'If private reporting is unavailable, open a public issue only to request a private channel. Do not include vulnerability details or exploit steps.',
    warning: 'Use synthetic examples only. Never attach real medical documents, screenshots of personal records, vault files, or backups to any support channel.',
  },
  'pt-br': {
    eyebrow: 'Suporte do projeto com melhor esforço', title: 'Obtenha ajuda sem compartilhar seus registros.',
    intro: 'Use as issues públicas para dúvidas gerais e o canal privado do GitHub para vulnerabilidades. No momento, não há e-mail de suporte nem compromisso de tempo de resposta.',
    generalTitle: 'Dúvidas e problemas', generalBody: 'Para dúvidas sobre o produto ou privacidade e para relatar erros, abra uma issue no GitHub. Informe a plataforma e a versão do app ou commit, com passos de reprodução quando útil.', action: 'Abrir issues no GitHub',
    securityTitle: 'Relatórios de segurança', securityBody: 'Na aba Security do repositório, selecione “Report a vulnerability”. Informe plataforma, versão ou commit, passos de reprodução, impacto e uma correção sugerida, se houver.', securityAction: 'Abrir a aba Security',
    fallback: 'Se o canal privado não estiver disponível, abra uma issue pública apenas para pedir um canal privado. Não inclua detalhes da vulnerabilidade nem passos de exploração.',
    warning: 'Use apenas exemplos fictícios. Nunca anexe documentos médicos reais, capturas de registros pessoais, arquivos do cofre ou backups em nenhum canal de suporte.',
  },
  es: {
    eyebrow: 'Soporte del proyecto con el mejor esfuerzo', title: 'Recibe ayuda sin compartir tus registros.',
    intro: 'Utiliza las issues públicas para preguntas generales y el canal privado de GitHub para vulnerabilidades. Actualmente no hay correo de soporte ni compromiso de tiempo de respuesta.',
    generalTitle: 'Preguntas y errores', generalBody: 'Para preguntas sobre el producto o la privacidad, o para informar de un error, abre una issue en GitHub. Indica la plataforma y la versión de la app o commit, con pasos de reproducción cuando sea útil.', action: 'Abrir issues en GitHub',
    securityTitle: 'Informes de seguridad', securityBody: 'En la pestaña Security del repositorio, selecciona “Report a vulnerability”. Indica plataforma, versión o commit, pasos de reproducción, impacto y una corrección sugerida si la tienes.', securityAction: 'Abrir la pestaña Security',
    fallback: 'Si el canal privado no está disponible, abre una issue pública solo para solicitar un canal privado. No incluyas detalles de la vulnerabilidad ni pasos de explotación.',
    warning: 'Usa solo ejemplos ficticios. Nunca adjuntes documentos médicos reales, capturas de registros personales, archivos del almacenamiento ni copias de seguridad en ningún canal de soporte.',
  },
};
