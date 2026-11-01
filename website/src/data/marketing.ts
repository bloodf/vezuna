import type { Locale } from '../lib/i18n';

export interface MarketingCopy {
  nav: { labs: string; medications: string; device: string; privacy: string; faq: string; docs: string; menu: string };
  hero: { eyebrow: string; title: string; body: string; primary: string; secondary: string; note: string };
  facts: string[];
  workspace: {
    eyebrow: string; title: string; intro: string;
    tabs: { id: 'labs' | 'meds' | 'device' | 'household'; label: string; title: string; description: string }[];
    syntheticNote: string;
  };
  lab: {
    title: string; test: string; unit: string; firstValue: string; firstDate: string;
    secondValue: string; secondDate: string; reviewStatus: string; chartSummary: string;
  };
  meds: { title: string; schedule: { time: string; name: string; detail: string; state: string }[]; note: string };
  device: { title: string; metrics: { label: string; value: string; unit: string; note: string }[]; permissionNote: string };
  household: { title: string; profiles: { name: string; detail: string }[]; exportsTitle: string; exportsBody: string };
  journey: { eyebrow: string; title: string; steps: { title: string; body: string }[] };
  gallery: { title: string; caption: string; screenshots: { src: string; alt: string; label: string }[] };
  privacy: { title: string; body: string; points: { title: string; body: string }[] };
  availability: { title: string; body: string; github: string; appStore: string; googlePlay: string; pending: string };
  faq: { title: string; items: { question: string; answer: string }[] };
  footer: { tagline: string; docs: string; support: string; privacy: string; source: string; notices: string; medical: string };
}

const screenshots = {
  home: '/assets/android-home.png',
  labs: '/assets/android-labs.png',
  settings: '/assets/android-settings.png',
};

export const marketingCopy: Record<Locale, MarketingCopy> = {
  en: {
    nav: { labs: 'Lab reports', medications: 'Medications', device: 'Device data', privacy: 'Privacy', faq: 'FAQ', docs: 'Documentation', menu: 'Menu' },
    hero: {
      eyebrow: 'A household health record workspace',
      title: 'Keep everyday health records together.',
      body: 'Organize lab reports, medications, weight, and device health data for your household, with separate profiles and records you can review.',
      primary: 'Explore the workspace', secondary: 'Read the documentation',
      note: 'Release preparation continues while physical-device checks and store review are pending.',
    },
    facts: ['Separate household profiles', 'Manual review before comparison', 'PDF and CSV exports'],
    workspace: {
      eyebrow: 'One workspace, four record types',
      title: 'Start with the record you need.',
      intro: 'Move between lab results, medication schedules, device metrics, and household profiles without mixing records.',
      tabs: [
        { id: 'labs', label: 'Labs', title: 'Review lab reports', description: 'Check imported values, confirm them manually, and compare confirmed results.' },
        { id: 'meds', label: 'Medications', title: 'Record medication routines', description: 'Keep entered schedules and mark actions as taken, skipped, or snoozed.' },
        { id: 'device', label: 'Device', title: 'Bring device metrics into view', description: 'Enter weight, heart rate, and steps manually or allow optional read-only imports.' },
        { id: 'household', label: 'Household', title: 'Keep each profile separate', description: 'Organize records by person and export selected information as PDF or CSV.' },
      ],
      syntheticNote: 'All examples below use synthetic records and do not provide medical guidance.',
    },
    lab: {
      title: 'Confirmed result comparison', test: 'Hemoglobin', unit: 'g/dL',
      firstValue: '13.2', firstDate: '12 Jan 2026', secondValue: '13.8', secondDate: '12 Apr 2026',
      reviewStatus: 'Manually confirmed against the original report',
      chartSummary: 'Two confirmed synthetic results: 13.2 and 13.8 g/dL.',
    },
    meds: {
      title: 'Recorded schedule',
      schedule: [
        { time: '08:00', name: 'Demo Medicine', detail: 'Recorded schedule', state: 'Taken' },
        { time: '13:00', name: 'Demo Medicine', detail: 'Recorded schedule', state: 'Skipped' },
        { time: '20:00', name: 'Demo Medicine', detail: 'Recorded schedule', state: 'Snoozed' },
      ],
      note: 'Synthetic schedule for demonstrating record actions only. No dosing advice is provided.',
    },
    device: {
      title: 'Device health data',
      metrics: [
        { label: 'Weight', value: '72.4', unit: 'kg', note: 'Synthetic manual record' },
        { label: 'Heart rate', value: '68', unit: 'bpm', note: 'Synthetic imported record' },
        { label: 'Steps', value: '6,842', unit: 'steps', note: 'Synthetic imported record' },
      ],
      permissionNote: 'Optional read-only imports use Health Connect or HealthKit. Denying permission leaves manual records usable.',
    },
    household: {
      title: 'Separate profiles for the household',
      profiles: [
        { name: 'Alex', detail: 'Separate lab reports and medication records' },
        { name: 'Sam', detail: 'Separate weight and device health records' },
      ],
      exportsTitle: 'PDF and CSV exports',
      exportsBody: 'Create a PDF report or CSV file from selected records, then choose where to share or store it.',
    },
    journey: {
      eyebrow: 'From document to comparison',
      title: 'Review each result before it joins the record.',
      steps: [
        { title: 'Import an image or PDF', body: 'Add a lab report from a camera capture, an image, or a PDF for on-device text extraction.' },
        { title: 'Review the OCR draft', body: 'Compare the extracted test, value, unit, reference range, and date with the original. Correct anything before confirming.' },
        { title: 'Compare confirmed results', body: 'Only manually confirmed results join comparisons, keeping each original value and report reference visible.' },
      ],
    },
    gallery: {
      title: 'A look at the current app',
      caption: 'Actual Android development-build screens with synthetic data. Physical-device checks and store distribution remain pending.',
      screenshots: [
        { src: screenshots.home, alt: 'Development-build household dashboard with synthetic records', label: 'Household overview' },
        { src: screenshots.labs, alt: 'Development-build lab records with synthetic results', label: 'Lab records' },
        { src: screenshots.settings, alt: 'Development-build settings with privacy and export controls', label: 'Privacy and exports' },
      ],
    },
    privacy: {
      title: 'Local records with clear export boundaries',
      body: 'Vezuna keeps household records on your device and leaves optional imports and sharing under your control.',
      points: [
        { title: 'Local storage', body: 'Records stay in an encrypted, profile-separated vault on your device. No independent security audit is claimed.' },
        { title: 'Optional imports', body: 'Health Connect and HealthKit access is read-only and optional. Manual records remain available if permission is denied.' },
        { title: 'Plaintext exports', body: 'PDF and CSV exports are plaintext. Their storage and protection depend on where you choose to save or share them.' },
      ],
    },
    availability: {
      title: 'Release preparation is still underway',
      body: 'Follow source development on GitHub while release preparation continues. App Store and Google Play versions are unavailable.',
      github: 'View on GitHub', appStore: 'App Store unavailable', googlePlay: 'Google Play unavailable', pending: 'Store availability pending',
    },
    faq: {
      title: 'Common questions',
      items: [
        { question: 'When will Vezuna be available?', answer: 'Vezuna is in release preparation. No stable release is published, physical-device checks remain pending, and app store versions are unavailable.' },
        { question: 'Which devices are supported?', answer: 'The source targets Android 8.0 or later and iOS 18.5 or later on iPhone. Physical-device release checks are still pending.' },
        { question: 'Which languages are included?', answer: 'The app and essential website guidance are available in English, Brazilian Portuguese, and Spanish.' },
        { question: 'Which permissions are optional?', answer: 'Health Connect and HealthKit imports are optional and read-only. Camera, selected images, and notifications support their specific workflows. Denying health access leaves manual records usable.' },
        { question: 'How do backups work?', answer: 'In-app backups are encrypted for restoration with the same device key. They do not provide portable recovery on another device or app and do not protect against device loss.' },
        { question: 'Does Vezuna provide medical guidance?', answer: 'No. Vezuna is a record organizer. It does not diagnose conditions, recommend treatment, or choose medication doses. Confirm imported details against the original report.' },
      ],
    },
    footer: {
      tagline: 'Household health records, kept on your device.', docs: 'Documentation', support: 'Support', privacy: 'Privacy', source: 'Source code', notices: 'Third-party notices',
      medical: 'Vezuna organizes personal health records and does not replace advice from a qualified healthcare professional.',
    },
  },
  'pt-br': {
    nav: { labs: 'Exames', medications: 'Medicamentos', device: 'Dados do dispositivo', privacy: 'Privacidade', faq: 'Perguntas frequentes', docs: 'Documentação', menu: 'Menu' },
    hero: {
      eyebrow: 'Um espaço para os registros de saúde da família',
      title: 'Mantenha os registros de saúde do dia a dia juntos.',
      body: 'Organize resultados de exames, medicamentos, peso e dados de saúde de dispositivos para sua família, com perfis separados e registros que você pode revisar.',
      primary: 'Conhecer o espaço de trabalho', secondary: 'Ler a documentação',
      note: 'O Vezuna está em preparação para lançamento. Nenhuma versão estável foi publicada, e as lojas de apps estão indisponíveis.',
    },
    facts: ['Perfis separados para a família', 'Revisão manual antes da comparação', 'Exportações em PDF e CSV'],
    workspace: {
      eyebrow: 'Um espaço, quatro tipos de registro',
      title: 'Comece pelo registro de que você precisa.',
      intro: 'Alterne entre exames, horários de medicamentos, métricas de dispositivos e perfis da família sem misturar os registros.',
      tabs: [
        { id: 'labs', label: 'Exames', title: 'Revise resultados de exames', description: 'Confira os valores importados, confirme manualmente e compare resultados confirmados.' },
        { id: 'meds', label: 'Medicamentos', title: 'Registre rotinas de medicamentos', description: 'Guarde os horários informados e marque ações como tomado, pulado ou adiado.' },
        { id: 'device', label: 'Dispositivo', title: 'Veja as métricas do dispositivo', description: 'Registre peso, frequência cardíaca e passos manualmente ou permita importações opcionais somente de leitura.' },
        { id: 'household', label: 'Família', title: 'Mantenha cada perfil separado', description: 'Organize os registros por pessoa e exporte as informações selecionadas em PDF ou CSV.' },
      ],
      syntheticNote: 'Todos os exemplos abaixo usam registros fictícios e não oferecem orientação médica.',
    },
    lab: {
      title: 'Comparação de resultados confirmados', test: 'Hemoglobina', unit: 'g/dL',
      firstValue: '13,2', firstDate: '12 de jan. de 2026', secondValue: '13,8', secondDate: '12 de abr. de 2026',
      reviewStatus: 'Confirmado manualmente com o relatório original',
      chartSummary: 'Dois resultados fictícios confirmados: 13,2 e 13,8 g/dL.',
    },
    meds: {
      title: 'Horário registrado',
      schedule: [
        { time: '08:00', name: 'Demo Medicine', detail: 'Horário registrado', state: 'Tomado' },
        { time: '13:00', name: 'Demo Medicine', detail: 'Horário registrado', state: 'Pulado' },
        { time: '20:00', name: 'Demo Medicine', detail: 'Horário registrado', state: 'Adiado' },
      ],
      note: 'Horário fictício apenas para demonstrar ações de registro. Nenhuma orientação de dose é fornecida.',
    },
    device: {
      title: 'Dados de saúde do dispositivo',
      metrics: [
        { label: 'Peso', value: '72,4', unit: 'kg', note: 'Registro manual fictício' },
        { label: 'Frequência cardíaca', value: '68', unit: 'bpm', note: 'Registro importado fictício' },
        { label: 'Passos', value: '6.842', unit: 'passos', note: 'Registro importado fictício' },
      ],
      permissionNote: 'As importações opcionais somente de leitura usam Health Connect ou HealthKit. Recusar a permissão mantém os registros manuais utilizáveis.',
    },
    household: {
      title: 'Perfis separados para a família',
      profiles: [
        { name: 'Alex', detail: 'Exames e registros de medicamentos separados' },
        { name: 'Sam', detail: 'Registros de peso e dados de saúde separados' },
      ],
      exportsTitle: 'Exportações em PDF e CSV',
      exportsBody: 'Crie um relatório em PDF ou arquivo CSV com registros selecionados e escolha onde compartilhar ou guardar.',
    },
    journey: {
      eyebrow: 'Do documento à comparação',
      title: 'Revise cada resultado antes de incluí-lo no registro.',
      steps: [
        { title: 'Importe uma imagem ou PDF', body: 'Adicione um relatório pela câmera, por uma imagem ou PDF para extrair o texto no dispositivo.' },
        { title: 'Revise o rascunho de OCR', body: 'Compare exame, valor, unidade, intervalo de referência e data com o original. Corrija qualquer item antes de confirmar.' },
        { title: 'Compare resultados confirmados', body: 'Somente resultados confirmados manualmente entram nas comparações, mantendo visíveis o valor original e a referência do relatório.' },
      ],
    },
    gallery: {
      title: 'Conheça o app atual',
      caption: 'Capturas do build de desenvolvimento para Android com dados fictícios. Os testes em dispositivos físicos e a distribuição nas lojas continuam pendentes.',
      screenshots: [
        { src: screenshots.home, alt: 'Painel familiar do build de desenvolvimento com registros fictícios', label: 'Visão geral da família' },
        { src: screenshots.labs, alt: 'Registros de exames do build de desenvolvimento com resultados fictícios', label: 'Registros de exames' },
        { src: screenshots.settings, alt: 'Configurações do build de desenvolvimento com controles de privacidade e exportação', label: 'Privacidade e exportações' },
      ],
    },
    privacy: {
      title: 'Registros locais com limites claros para exportação',
      body: 'O Vezuna mantém os registros da família no seu dispositivo e deixa as importações opcionais e o compartilhamento sob seu controle.',
      points: [
        { title: 'Armazenamento local', body: 'Os registros ficam em um cofre criptografado no dispositivo, separados por perfil. Não há alegação de auditoria de segurança independente.' },
        { title: 'Importações opcionais', body: 'O acesso ao Health Connect e HealthKit é opcional e somente de leitura. Os registros manuais continuam disponíveis se a permissão for recusada.' },
        { title: 'Exportações em texto simples', body: 'Os arquivos PDF e CSV são texto simples. Seu armazenamento e proteção dependem de onde você decidir guardar ou compartilhar.' },
      ],
    },
    availability: {
      title: 'A preparação para lançamento continua',
      body: 'Nenhuma versão estável foi publicada. Acompanhe o desenvolvimento do código no GitHub; as versões para App Store e Google Play estão indisponíveis.',
      github: 'Ver no GitHub', appStore: 'App Store indisponível', googlePlay: 'Google Play indisponível', pending: 'Disponibilidade nas lojas pendente',
    },
    faq: {
      title: 'Perguntas frequentes',
      items: [
        { question: 'Quando o Vezuna estará disponível?', answer: 'O Vezuna está em preparação para lançamento. Nenhuma versão estável foi publicada, os testes em dispositivos físicos continuam pendentes e as versões nas lojas estão indisponíveis.' },
        { question: 'Quais dispositivos são compatíveis?', answer: 'O código atual é destinado ao Android 8.0 ou posterior e ao iOS 18.5 ou posterior no iPhone. Os testes de lançamento em dispositivos físicos continuam pendentes.' },
        { question: 'Quais idiomas estão incluídos?', answer: 'O app e as orientações essenciais do site estão disponíveis em inglês, português do Brasil e espanhol.' },
        { question: 'Quais permissões são opcionais?', answer: 'As importações do Health Connect e HealthKit são opcionais e somente de leitura. Câmera, imagens selecionadas e notificações apoiam fluxos específicos. Recusar o acesso à saúde mantém os registros manuais utilizáveis.' },
        { question: 'Como funcionam os backups?', answer: 'Os backups do app são criptografados para restauração com a chave do mesmo dispositivo. Não oferecem recuperação portátil em outro dispositivo ou app nem protegem contra a perda do aparelho.' },
        { question: 'O Vezuna oferece orientação médica?', answer: 'Não. O Vezuna organiza registros. Ele não diagnostica doenças, recomenda tratamentos nem escolhe doses de medicamentos. Confirme os dados importados com o relatório original.' },
      ],
    },
    footer: {
      tagline: 'Registros de saúde da família, guardados no seu dispositivo.', docs: 'Documentação', support: 'Suporte', privacy: 'Privacidade', source: 'Código-fonte', notices: 'Avisos de terceiros',
      medical: 'O Vezuna organiza registros pessoais de saúde e não substitui a orientação de um profissional de saúde qualificado.',
    },
  },
  es: {
    nav: { labs: 'Análisis', medications: 'Medicamentos', device: 'Datos del dispositivo', privacy: 'Privacidad', faq: 'Preguntas frecuentes', docs: 'Documentación', menu: 'Menú' },
    hero: {
      eyebrow: 'Un espacio para los registros de salud del hogar',
      title: 'Mantén juntos los registros de salud diarios.',
      body: 'Organiza informes de laboratorio, medicamentos, peso y datos de salud de dispositivos para tu hogar, con perfiles separados y registros que puedes revisar.',
      primary: 'Explorar el espacio de trabajo', secondary: 'Leer la documentación',
      note: 'Vezuna está en preparación para su lanzamiento. No se ha publicado una versión estable y las tiendas de apps no están disponibles.',
    },
    facts: ['Perfiles separados para el hogar', 'Revisión manual antes de comparar', 'Exportaciones en PDF y CSV'],
    workspace: {
      eyebrow: 'Un espacio, cuatro tipos de registro',
      title: 'Empieza por el registro que necesitas.',
      intro: 'Cambia entre análisis, horarios de medicamentos, métricas de dispositivos y perfiles del hogar sin mezclar registros.',
      tabs: [
        { id: 'labs', label: 'Análisis', title: 'Revisa informes de laboratorio', description: 'Comprueba los valores importados, confírmalos manualmente y compara resultados confirmados.' },
        { id: 'meds', label: 'Medicamentos', title: 'Registra rutinas de medicamentos', description: 'Guarda los horarios introducidos y marca acciones como tomado, omitido o pospuesto.' },
        { id: 'device', label: 'Dispositivo', title: 'Consulta métricas del dispositivo', description: 'Registra peso, frecuencia cardíaca y pasos manualmente o permite importaciones opcionales de solo lectura.' },
        { id: 'household', label: 'Hogar', title: 'Mantén cada perfil separado', description: 'Organiza los registros por persona y exporta la información seleccionada como PDF o CSV.' },
      ],
      syntheticNote: 'Todos los ejemplos siguientes usan registros sintéticos y no ofrecen orientación médica.',
    },
    lab: {
      title: 'Comparación de resultados confirmados', test: 'Hemoglobina', unit: 'g/dL',
      firstValue: '13,2', firstDate: '12 ene 2026', secondValue: '13,8', secondDate: '12 abr 2026',
      reviewStatus: 'Confirmado manualmente con el informe original',
      chartSummary: 'Dos resultados sintéticos confirmados: 13,2 y 13,8 g/dL.',
    },
    meds: {
      title: 'Horario registrado',
      schedule: [
        { time: '08:00', name: 'Demo Medicine', detail: 'Horario registrado', state: 'Tomado' },
        { time: '13:00', name: 'Demo Medicine', detail: 'Horario registrado', state: 'Omitido' },
        { time: '20:00', name: 'Demo Medicine', detail: 'Horario registrado', state: 'Pospuesto' },
      ],
      note: 'Horario sintético solo para demostrar acciones de registro. No se ofrecen recomendaciones de dosis.',
    },
    device: {
      title: 'Datos de salud del dispositivo',
      metrics: [
        { label: 'Peso', value: '72,4', unit: 'kg', note: 'Registro manual sintético' },
        { label: 'Frecuencia cardíaca', value: '68', unit: 'bpm', note: 'Registro importado sintético' },
        { label: 'Pasos', value: '6.842', unit: 'pasos', note: 'Registro importado sintético' },
      ],
      permissionNote: 'Las importaciones opcionales de solo lectura usan Health Connect o HealthKit. Rechazar el permiso mantiene utilizables los registros manuales.',
    },
    household: {
      title: 'Perfiles separados para el hogar',
      profiles: [
        { name: 'Alex', detail: 'Informes de laboratorio y medicamentos separados' },
        { name: 'Sam', detail: 'Registros de peso y datos de salud separados' },
      ],
      exportsTitle: 'Exportaciones en PDF y CSV',
      exportsBody: 'Crea un informe PDF o archivo CSV con registros seleccionados y elige dónde compartirlo o guardarlo.',
    },
    journey: {
      eyebrow: 'Del documento a la comparación',
      title: 'Revisa cada resultado antes de añadirlo al registro.',
      steps: [
        { title: 'Importa una imagen o PDF', body: 'Añade un informe desde la cámara, una imagen o un PDF para extraer el texto en el dispositivo.' },
        { title: 'Revisa el borrador de OCR', body: 'Compara análisis, valor, unidad, intervalo de referencia y fecha con el original. Corrige cualquier dato antes de confirmar.' },
        { title: 'Compara resultados confirmados', body: 'Solo los resultados confirmados manualmente se incluyen en las comparaciones, con el valor original y la referencia del informe visibles.' },
      ],
    },
    gallery: {
      title: 'Una mirada a la app actual',
      caption: 'Pantallas reales de la compilación de desarrollo para Android con datos sintéticos. Las pruebas en dispositivos físicos y la distribución en tiendas siguen pendientes.',
      screenshots: [
        { src: screenshots.home, alt: 'Panel del hogar de la compilación de desarrollo con registros sintéticos', label: 'Resumen del hogar' },
        { src: screenshots.labs, alt: 'Registros de laboratorio de la compilación de desarrollo con resultados sintéticos', label: 'Registros de laboratorio' },
        { src: screenshots.settings, alt: 'Ajustes de la compilación de desarrollo con controles de privacidad y exportación', label: 'Privacidad y exportaciones' },
      ],
    },
    privacy: {
      title: 'Registros locales con límites claros de exportación',
      body: 'Vezuna guarda los registros del hogar en tu dispositivo y deja las importaciones opcionales y el uso compartido bajo tu control.',
      points: [
        { title: 'Almacenamiento local', body: 'Los registros se guardan en un almacenamiento cifrado y separado por perfil en tu dispositivo. No se afirma que exista una auditoría de seguridad independiente.' },
        { title: 'Importaciones opcionales', body: 'El acceso a Health Connect y HealthKit es opcional y de solo lectura. Los registros manuales siguen disponibles si rechazas el permiso.' },
        { title: 'Exportaciones en texto sin cifrar', body: 'Los archivos PDF y CSV están en texto sin cifrar. Su almacenamiento y protección dependen de dónde decidas guardarlos o compartirlos.' },
      ],
    },
    availability: {
      title: 'La preparación del lanzamiento continúa',
      body: 'No se ha publicado una versión estable. Sigue el desarrollo del código en GitHub; las versiones para App Store y Google Play no están disponibles.',
      github: 'Ver en GitHub', appStore: 'App Store no disponible', googlePlay: 'Google Play no disponible', pending: 'Disponibilidad en tiendas pendiente',
    },
    faq: {
      title: 'Preguntas frecuentes',
      items: [
        { question: '¿Cuándo estará disponible Vezuna?', answer: 'Vezuna está en preparación para su lanzamiento. No se ha publicado una versión estable, las pruebas en dispositivos físicos siguen pendientes y las versiones en tiendas no están disponibles.' },
        { question: '¿Qué dispositivos son compatibles?', answer: 'El código actual está destinado a Android 8.0 o posterior y a iOS 18.5 o posterior en iPhone. Las pruebas de lanzamiento en dispositivos físicos siguen pendientes.' },
        { question: '¿Qué idiomas están incluidos?', answer: 'La app y las guías esenciales del sitio están disponibles en inglés, portugués de Brasil y español.' },
        { question: '¿Qué permisos son opcionales?', answer: 'Las importaciones de Health Connect y HealthKit son opcionales y de solo lectura. Cámara, imágenes seleccionadas y notificaciones apoyan flujos específicos. Rechazar el acceso a salud mantiene utilizables los registros manuales.' },
        { question: '¿Cómo funcionan las copias de seguridad?', answer: 'Las copias de la app están cifradas para restaurarse con la clave del mismo dispositivo. No ofrecen recuperación portátil en otro dispositivo o app ni protegen contra la pérdida del dispositivo.' },
        { question: '¿Vezuna ofrece orientación médica?', answer: 'No. Vezuna organiza registros. No diagnostica enfermedades, recomienda tratamientos ni elige dosis de medicamentos. Confirma los datos importados con el informe original.' },
      ],
    },
    footer: {
      tagline: 'Los registros de salud de tu hogar, en tu dispositivo.', docs: 'Documentación', support: 'Soporte', privacy: 'Privacidad', source: 'Código fuente', notices: 'Avisos de terceros',
      medical: 'Vezuna organiza registros personales de salud y no reemplaza la orientación de un profesional de la salud.',
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
    warning: 'Use synthetic examples only. Never attach actual medical documents, screenshots of personal records, vault files, or backups to any support channel.',
  },
  'pt-br': {
    eyebrow: 'Suporte do projeto com melhor esforço', title: 'Obtenha ajuda sem compartilhar seus registros.',
    intro: 'Use as issues públicas para dúvidas gerais e o canal privado do GitHub para vulnerabilidades. No momento, não há e-mail de suporte nem compromisso de tempo de resposta.',
    generalTitle: 'Dúvidas e problemas', generalBody: 'Para dúvidas sobre o produto ou privacidade e para relatar erros, abra uma issue no GitHub. Informe a plataforma e a versão do app ou commit, com passos de reprodução quando útil.', action: 'Abrir issues no GitHub',
    securityTitle: 'Relatórios de segurança', securityBody: 'Na aba Security do repositório, selecione “Report a vulnerability”. Informe plataforma, versão ou commit, passos de reprodução, impacto e uma correção sugerida, se houver.', securityAction: 'Abrir a aba Security',
    fallback: 'Se o canal privado não estiver disponível, abra uma issue pública apenas para pedir um canal privado. Não inclua detalhes da vulnerabilidade nem passos de exploração.',
    warning: 'Use apenas exemplos fictícios. Nunca anexe documentos médicos de pessoas, capturas de registros pessoais, arquivos do cofre ou backups em nenhum canal de suporte.',
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
