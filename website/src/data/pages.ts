import type { Locale } from '../lib/i18n';
import type { Screenshot } from './marketing';

/**
 * Copy for the standalone product pages: open source, screens, how it works, and local data.
 *
 * Every claim below is checked against the repository before it was written:
 * - MIT scope comes from LICENSE and the table in THIRD_PARTY_NOTICES.md.
 * - Non-MIT terms (Noto Sans OFL, vendored Play Licensing, ML Kit and Play Core terms,
 *   GGUF models, Llamatik native libraries) come from the same notices file.
 * - Build requirements come from docs/DEVELOPMENT.md and the developer guide.
 * - Backup passphrase length, backup size limit, and app-lock behaviour come from the
 *   app string catalogs in shared/.../composeResources/values/strings.xml.
 * - The Android manifest removes android.permission.INTERNET from the merged manifest.
 * - README.md states that paid store distribution and open-source licensing are independent.
 *
 * The pages never promise a published build, a working production AI feature, or a
 * clinical outcome. The production model catalog is empty, so no AI feature is available.
 */

export interface Meta { title: string; description: string }
export interface Fact { term: string; value: string }
export interface TermRow { term: string; body: string }
export interface TermsRow { term: string; terms: string }
export interface DataRow { what: string; where: string; leaves: string }

export interface OpenSourceCopy {
  meta: Meta;
  hero: {
    eyebrow: string; title: string; body: string;
    facts: Fact[]; primary: string; secondary: string;
  };
  covered: { title: string; body: string; columns: { term: string; body: string }; rows: TermRow[] };
  excluded: { title: string; body: string; columns: { term: string; terms: string }; rows: TermsRow[]; note: string };
  build: { title: string; body: string; steps: { title: string; body: string }[]; cta: string; commands: { caption: string; lines: string[] } };
  contribute: { title: string; body: string; items: TermRow[]; cta: string };
  status: { title: string; body: string; items: string[] };
  next: { title: string; body: string };
  links: { repository: string; issues: string; security: string; contributing: string; notices: string; developers: string; license: string; guides: string; repositoryNotices: string };
}

export interface ScreensCopy {
  meta: Meta;
  hero: { eyebrow: string; title: string; body: string };
  gallery: { title: string; body: string };
  limits: { title: string; body: string; items: string[] };
  cta: { title: string; body: string; primary: string; secondary: string };
}

export interface HowItWorksCopy {
  meta: Meta;
  hero: { eyebrow: string; title: string; body: string };
  flow: { title: string; body: string; steps: { title: string; body: string }[] };
  reminders: { title: string; body: string; items: TermRow[] };
  exports: { title: string; body: string; items: TermRow[] };
  backups: { title: string; body: string; items: TermRow[] };
  controls: { title: string; body: string; items: TermRow[] };
  never: { title: string; body: string; items: string[] };
  links: { localData: string; openSource: string; screens: string };
}

export interface LocalDataCopy {
  meta: Meta;
  hero: { eyebrow: string; title: string; body: string };
  map: { title: string; body: string; columns: { what: string; where: string; leaves: string }; rows: DataRow[] };
  keys: { title: string; body: string; items: TermRow[] };
  permissions: { title: string; body: string; items: TermRow[] };
  deletion: { title: string; body: string; items: TermRow[] };
  site: { title: string; body: string };
  audit: { note: string };
  links: { policy: string; details: string; source: string };
}

export const openSourceCopy: Record<Locale, OpenSourceCopy> = {
  en: {
    meta: {
      title: 'Open source under MIT',
      description: 'The Vezuna app source is MIT licensed. Read the code, build it on your machine, and see exactly which parts keep different third-party terms.',
    },
    hero: {
      eyebrow: 'Open source',
      title: 'The app source is MIT licensed. Read it, build it, change it.',
      body: 'Vezuna is a native Kotlin Multiplatform project with an Android app and an Xcode iOS app. The source is public under the MIT license, with no contributor license agreement and no trademark rights granted. Store distribution and open-source licensing are two separate things: the MIT license lets you read and build the code, and it does not promise a published app.',
      facts: [
        { term: 'License', value: 'MIT for the app source and the original artwork' },
        { term: 'Languages', value: 'Kotlin, Swift, Python, TypeScript for this site' },
        { term: 'Store build', value: 'None published yet. Store review is still open' },
      ],
      primary: 'Read the developer guide',
      secondary: 'Browse the source repository',
    },
    covered: {
      title: 'What the MIT license covers',
      body: 'The MIT license in the repository root applies to the project source listed here. Original artwork carries its own MIT license without trademark rights.',
      columns: { term: 'Path', body: 'What it holds' },
      rows: [
        { term: 'shared/', body: 'Domain logic, the Compose interface, string catalogs for three languages, vault contracts, and the exporters.' },
        { term: 'composeApp/', body: 'The Android app, its application ID app.vezuna, and the native integrations, except the vendored code listed below.' },
        { term: 'iosApp/', body: 'The Xcode app, its bundle ID app.vezuna, the SwiftUI shell and the Kotlin bridge, except the bundled fonts.' },
        { term: 'tools/', body: 'The Python lab importer, the localization and branding checks, and the release checks.' },
        { term: 'scripts/', body: 'Repository build scripts, including the iOS build script.' },
        { term: 'fixtures/', body: 'Synthetic importer fixtures. Every value in them is fictional.' },
        { term: 'docs/', body: 'Development, releasing, and architecture guides.' },
        { term: 'website/', body: 'This site and its public documentation, built with Astro.' },
        { term: 'brand/', body: 'Original logos, marks, icons, and tokens under a separate MIT license that grants no trademark rights.' },
      ],
    },
    excluded: {
      title: 'What the MIT license does not cover',
      body: 'The MIT license never reaches third-party material. These components keep their own terms, and some of them are still unresolved release gates.',
      columns: { term: 'Component', terms: 'Terms that apply to it' },
      rows: [
        { term: 'Noto Sans fonts', terms: 'SIL Open Font License 1.1. The bundled font files are unmodified and the notice travels with them.' },
        { term: 'Vendored Google Play Licensing code', terms: 'Apache-2.0, with the license copy kept in the Android third-party folder.' },
        { term: 'ML Kit text recognition', terms: 'ML Kit Terms of Service for the Android OCR dependency. Not an open-source license.' },
        { term: 'Play Core asset delivery', terms: 'Play Core Software Development Kit Terms of Service. Not an open-source license.' },
        { term: 'Local model files (GGUF)', terms: 'Never committed. The models/ directory is git-ignored, and the repository ships no model. A GGUF conversion can carry terms from both the base model and the converter.' },
        { term: 'Llamatik prebuilt native libraries', terms: 'The pinned upstream tag declares MIT while the published package metadata declares Apache-2.0. The binary-to-source mapping is still unresolved, and it stays a release gate for Android and iOS.' },
      ],
      note: 'The complete notice set lives in THIRD_PARTY_NOTICES.md in the repository, and the app itself can show it under More, then Third-party notices. This site repeats the parts that matter for reading the code.',
    },
    build: {
      title: 'Build it yourself',
      body: 'The project builds with the Gradle wrapper already in the repository. You need a local toolchain; nothing is downloaded from a Vezuna server.',
      steps: [
        { title: 'Install the toolchain', body: 'JDK 17, Android SDK API 37 with minSdk 26, and Python 3. The iOS app needs macOS with Xcode and a deployment target of 18.5.' },
        { title: 'Compile and run the tests', body: './gradlew :shared:jvmTest :composeApp:assembleDebug :composeApp:testDebugUnitTest :composeApp:assembleDebugAndroidTest builds the shared module, the Android app, and the test APK.' },
        { title: 'Run the synthetic checks', body: 'python3 tools/lab_importer/test_importer.py, python3 tools/localization/check_localization.py, and python3 tools/branding/check_brand.py scan the fixtures, the three language catalogs, and the brand assets.' },
        { title: 'Build the website', body: 'Inside website/, npm ci then npm run build produces the static site you are reading. The site needs no account, no analytics, and no backend.' },
      ],
      cta: 'Open the developer guide',
      commands: {
        caption: 'The same commands work on a clean checkout, from the repository root.',
        lines: [
          './gradlew :shared:jvmTest :composeApp:assembleDebug',
          'python3 tools/lab_importer/test_importer.py',
          'python3 tools/localization/check_localization.py',
          'cd website && npm ci && npm run build',
        ],
      },
    },
    contribute: {
      title: 'How to contribute',
      body: 'The project is maintained in the open. Read the contribution rules before you start, and keep one change per pull request.',
      items: [
        { term: 'Open an issue first', body: 'Non-trivial changes start as an issue, so the design is agreed before the code is written.' },
        { term: 'Match the existing structure', body: 'Shared logic belongs in shared/commonMain. Platform code goes behind expect/actual or the existing bridge classes.' },
        { term: 'Translate every string', body: 'User-visible text lives in the string catalogs for English, Portuguese, and Spanish. tools/localization/check_localization.py checks parity.' },
        { term: 'Use synthetic data only', body: 'Never commit real health data. Lab reports, prescriptions, names, and screenshots in issues and pull requests must be fictional.' },
        { term: 'Keep the changelog honest', body: 'User-visible changes go under Unreleased in CHANGELOG.md, and the pull request states which checks were run and which could not be.' },
        { term: 'Report vulnerabilities privately', body: 'Security problems go through the repository Security tab, never a public issue.' },
      ],
      cta: 'Read CONTRIBUTING.md',
    },
    status: {
      title: 'Where the project actually stands',
      body: 'Open source is a fact about the license. It is not a statement that a finished app is available. These points are still open:',
      items: [
        'No stable release has been published. Signed store builds and store review are still pending, so there are no download links.',
        'The production model catalog is empty, so no AI feature is available. The inference code is in the repository, but no model pack is configured or delivered.',
        'The attribution and redistribution obligations for the prebuilt native libraries remain unresolved, and they are a release gate.',
        'Physical-device checks for permissions, notification timing, backups, exports, and Health Connect and Apple Health reads are still to be run.',
        'The app stores health records and organizes them. It does not diagnose conditions, recommend treatment, or change doses.',
      ],
    },
    next: {
      title: 'Where to go next',
      body: 'The source, the issue tracker, the contribution rules, the license, and the complete third-party notice are all one click away.',
    },
    links: {
      repository: 'Source repository',
      issues: 'Issue tracker',
      security: 'Report a vulnerability',
      contributing: 'CONTRIBUTING.md',
      notices: 'THIRD_PARTY_NOTICES.md',
      developers: 'Developer guide',
      license: 'MIT license',
      guides: 'Development guide in the repository',
      repositoryNotices: 'Read the complete notice in the repository',
    },
  },
  'pt-br': {
    meta: {
      title: 'Código aberto sob MIT',
      description: 'O código do app Vezuna é MIT. Leia o código, compile na sua máquina e veja exatamente quais partes têm termos de terceiros diferentes.',
    },
    hero: {
      eyebrow: 'Código aberto',
      title: 'O código do app é MIT. Leia, compile, altere.',
      body: 'O Vezuna é um projeto nativo em Kotlin Multiplatform, com app Android e app iPhone em Xcode. O código é público sob a licença MIT, sem CLA e sem direito de marca registrada. Distribuição em loja e código aberto são coisas separadas: a licença MIT permite ler e compilar o código, e não promete um app publicado.',
      facts: [
        { term: 'Licença', value: 'MIT para o código do app e a arte original' },
        { term: 'Linguagens', value: 'Kotlin, Swift, Python, TypeScript neste site' },
        { term: 'Versão em loja', value: 'Nenhuma publicada ainda. A análise das lojas segue aberta' },
      ],
      primary: 'Ler o guia do desenvolvedor (em inglês)',
      secondary: 'Ver o repositório',
    },
    covered: {
      title: 'O que a licença MIT cobre',
      body: 'A licença MIT na raiz do repositório vale para o código do projeto listado aqui. A arte original tem a própria licença MIT, sem direito de marca registrada.',
      columns: { term: 'Caminho', body: 'O que guarda' },
      rows: [
        { term: 'shared/', body: 'Regras de domínio, interface Compose, catálogos de texto em três idiomas, contratos do cofre e exportadores.' },
        { term: 'composeApp/', body: 'O app Android, o application ID app.vezuna e as integrações nativas, exceto o código vendorizado listado abaixo.' },
        { term: 'iosApp/', body: 'O app Xcode, o bundle ID app.vezuna, o shell SwiftUI e a ponte Kotlin, exceto as fontes embutidas.' },
        { term: 'tools/', body: 'O importador de laudos em Python, as checagens de localização e marca e as checagens de release.' },
        { term: 'scripts/', body: 'Scripts de build do repositório, incluindo o script de build iOS.' },
        { term: 'fixtures/', body: 'Fixtures sintéticos do importador. Todo valor é fictício.' },
        { term: 'docs/', body: 'Guias de desenvolvimento, release e arquitetura.' },
        { term: 'website/', body: 'Este site e a documentação pública, construídos com Astro.' },
        { term: 'brand/', body: 'Logos, marcas, ícones e tokens originais sob uma licença MIT separada, sem direito de marca registrada.' },
      ],
    },
    excluded: {
      title: 'O que a licença MIT não cobre',
      body: 'A licença MIT nunca alcança material de terceiros. Estes componentes mantêm os próprios termos, e alguns ainda são pendências de release.',
      columns: { term: 'Componente', terms: 'Termos que valem para ele' },
      rows: [
        { term: 'Fontes Noto Sans', terms: 'SIL Open Font License 1.1. Os arquivos são distributions sem modificação e o aviso vai junto.' },
        { term: 'Código vendorizado do Google Play Licensing', terms: 'Apache-2.0, com a cópia da licença na pasta de terceiros do Android.' },
        { term: 'Reconhecimento de texto ML Kit', terms: 'Termos de Serviço do ML Kit para a dependência de OCR no Android. Não é uma licença open source.' },
        { term: 'Play Core asset delivery', terms: 'Termos de Serviço do Play Core SDK. Não é uma licença open source.' },
        { term: 'Arquivos de modelo local (GGUF)', terms: 'Nunca entram no repositório. A pasta models/ é ignorada pelo git e o repositório não distribui nenhum modelo. Uma conversão GGUF carrega termos do modelo base e do conversor.' },
        { term: 'Bibliotecas nativas pré-compiladas do Llamatik', terms: 'A tag upstream fixada declara MIT, enquanto os metadados do pacote publicado declaram Apache-2.0. O mapeamento entre binário e fonte continua sem resolução e é pendência de release em Android e iOS.' },
      ],
      note: 'O conjunto completo de avisos está em THIRD_PARTY_NOTICES.md no repositório, e o próprio app mostra em Mais, depois Avisos de terceiros. Este site repete as partes que importam para ler o código.',
    },
    build: {
      title: 'Compile na sua máquina',
      body: 'O projeto compila com o wrapper do Gradle que já está no repositório. Você precisa de um toolchain local; nada é baixado de um servidor do Vezuna.',
      steps: [
        { title: 'Instale o toolchain', body: 'JDK 17, Android SDK API 37 com minSdk 26 e Python 3. O app iOS exige macOS com Xcode e deployment target 18.5.' },
        { title: 'Compile e rode os testes', body: './gradlew :shared:jvmTest :composeApp:assembleDebug :composeApp:testDebugUnitTest :composeApp:assembleDebugAndroidTest monta o módulo compartilhado, o app Android e o APK de teste.' },
        { title: 'Rode as checagens sintéticas', body: 'python3 tools/lab_importer/test_importer.py, python3 tools/localization/check_localization.py e python3 tools/branding/check_brand.py verificam os fixtures, os catálogos dos três idiomas e os arquivos de marca.' },
        { title: 'Monte o site', body: 'Dentro de website/, npm ci e depois npm run build geram o site estático que você está lendo. O site não usa conta, analytics nem backend.' },
      ],
      cta: 'Abrir o guia do desenvolvedor (em inglês)',
      commands: {
        caption: 'Os mesmos comandos funcionam em um checkout limpo, a partir da raiz do repositório.',
        lines: [
          './gradlew :shared:jvmTest :composeApp:assembleDebug',
          'python3 tools/lab_importer/test_importer.py',
          'python3 tools/localization/check_localization.py',
          'cd website && npm ci && npm run build',
        ],
      },
    },
    contribute: {
      title: 'Como contribuir',
      body: 'O projeto é mantido em público. Leia as regras de contribuição antes de começar e mantenha uma mudança por pull request.',
      items: [
        { term: 'Abra uma issue antes', body: 'Mudanças grandes começam como issue, para o desenho ser combinado antes do código.' },
        { term: 'Siga a estrutura existente', body: 'Lógica compartilhada fica em shared/commonMain. Código de plataforma fica atrás de expect/actual ou das pontes já existentes.' },
        { term: 'Traduza todo texto', body: 'Texto visível ao usuário vive nos catálogos de inglês, português e espanhol. tools/localization/check_localization.py confere a paridade.' },
        { term: 'Use só dados sintéticos', body: 'Nunca versione dados de saúde reais. Laudos, prescrições, nomes e capturas em issues e pull requests têm de ser fictícios.' },
        { term: 'Mantenha o changelog honesto', body: 'Mudanças visíveis ao usuário entram em Unreleased no CHANGELOG.md, e o pull request diz quais checagens rodaram e quais não.' },
        { term: 'Reporte vulnerabilidades em privado', body: 'Problemas de segurança vão pela aba Security do repositório, nunca por issue pública.' },
      ],
      cta: 'Ler o CONTRIBUTING.md',
    },
    status: {
      title: 'Em que ponto o projeto está',
      body: 'Código aberto é um fato sobre a licença. Não é a afirmação de que existe um app pronto. Estes pontos seguem abertos:',
      items: [
        'Nenhuma versão estável foi publicada. Builds assinados e a análise das lojas continuam pendentes, então não há links de download.',
        'O catálogo de modelos em produção está vazio, então nenhum recurso de IA está disponível. O código de inferência está no repositório, mas nenhum pacote de modelo está configurado ou distribuído.',
        'As obrigações de atribuição e redistribuição das bibliotecas nativas pré-compiladas continuam sem resolução e são pendência de release.',
        'As checagens em aparelho físico para permissões, horário das notificações, backups, exportações e leitura do Health Connect e do Apple Saúde ainda precisam rodar.',
        'O app guarda e organiza registros de saúde. Ele não diagnostica, não recomenda tratamento e não muda doses.',
      ],
    },
    next: {
      title: 'Para onde ir agora',
      body: 'O código, as issues, as regras de contribuição, a licença e o aviso completo de terceiros estão a um clique.',
    },
    links: {
      repository: 'Repositório do código',
      issues: 'Issues',
      security: 'Reportar uma vulnerabilidade',
      contributing: 'CONTRIBUTING.md',
      notices: 'THIRD_PARTY_NOTICES.md',
      developers: 'Guia do desenvolvedor',
      license: 'Licença MIT',
      guides: 'Guia de desenvolvimento no repositório',
      repositoryNotices: 'Ler o aviso completo no repositório',
    },
  },
  es: {
    meta: {
      title: 'Código abierto bajo MIT',
      description: 'El código de la app Vezuna es MIT. Lee el código, compílalo en tu equipo y mira qué partes conservan términos de terceros distintos.',
    },
    hero: {
      eyebrow: 'Código abierto',
      title: 'El código de la app es MIT. Léelo, compílalo, cámbialo.',
      body: 'Vezuna es un proyecto nativo de Kotlin Multiplatform, con una app para Android y otra para iPhone en Xcode. El código es público bajo la licencia MIT, sin CLA y sin derechos de marca registrada. La distribución en tiendas y el código abierto son dos cosas distintas: la licencia MIT permite leer y compilar el código, y no promete una app publicada.',
      facts: [
        { term: 'Licencia', value: 'MIT para el código de la app y el arte original' },
        { term: 'Lenguajes', value: 'Kotlin, Swift, Python, TypeScript en este sitio' },
        { term: 'Versión en tienda', value: 'Ninguna publicada todavía. La revisión de tiendas sigue abierta' },
      ],
      primary: 'Leer la guía del desarrollador (en inglés)',
      secondary: 'Ver el repositorio',
    },
    covered: {
      title: 'Qué cubre la licencia MIT',
      body: 'La licencia MIT de la raíz del repositorio se aplica al código del proyecto que aparece aquí. El arte original tiene su propia licencia MIT, sin derechos de marca registrada.',
      columns: { term: 'Ruta', body: 'Qué contiene' },
      rows: [
        { term: 'shared/', body: 'Lógica de dominio, la interfaz Compose, los catálogos de texto en tres idiomas, los contratos del almacén y los exportadores.' },
        { term: 'composeApp/', body: 'La app para Android, su application ID app.vezuna y las integraciones nativas, excepto el código de terceros incluido que se lista abajo.' },
        { term: 'iosApp/', body: 'La app de Xcode, su bundle ID app.vezuna, el shell de SwiftUI y el puente de Kotlin, excepto las fuentes incrustadas.' },
        { term: 'tools/', body: 'El importador de informes en Python, las comprobaciones de localización y de marca y las de publicación.' },
        { term: 'scripts/', body: 'Scripts de compilación del repositorio, incluido el de build de iOS.' },
        { term: 'fixtures/', body: 'Fixtures sintéticos del importador. Todos los valores son ficticios.' },
        { term: 'docs/', body: 'Guías de desarrollo, publicación y arquitectura.' },
        { term: 'website/', body: 'Este sitio y su documentación pública, construidos con Astro.' },
        { term: 'brand/', body: 'Logotipos, marcas, iconos y tokens originales bajo una licencia MIT aparte, sin derechos de marca registrada.' },
      ],
    },
    excluded: {
      title: 'Qué no cubre la licencia MIT',
      body: 'La licencia MIT nunca alcanza material de terceros. Estos componentes conservan sus propios términos, y algunos siguen siendo pendientes de publicación.',
      columns: { term: 'Componente', terms: 'Términos que le aplican' },
      rows: [
        { term: 'Fuentes Noto Sans', terms: 'SIL Open Font License 1.1. Los archivos se distribuyen sin modificar y el aviso viaja con ellos.' },
        { term: 'Código incluido de Google Play Licensing', terms: 'Apache-2.0, con la copia de la licencia en la carpeta de terceros de Android.' },
        { term: 'Reconocimiento de texto de ML Kit', terms: 'Términos del servicio de ML Kit para la dependencia de OCR en Android. No es una licencia de código abierto.' },
        { term: 'Play Core asset delivery', terms: 'Términos del servicio del Play Core SDK. No es una licencia de código abierto.' },
        { term: 'Archivos de modelo local (GGUF)', terms: 'Nunca se versionan. El directorio models/ está ignorado por git y el repositorio no distribuye ningún modelo. Una conversión GGUF arrastra los términos del modelo base y del conversor.' },
        { term: 'Bibliotecas nativas precompiladas de Llamatik', terms: 'La etiqueta upstream fijada declara MIT, mientras que los metadatos del paquete publicado declaran Apache-2.0. La correspondencia entre binario y fuente sigue sin resolverse y es una pendiente de publicación en Android e iOS.' },
      ],
      note: 'El conjunto completo de avisos está en THIRD_PARTY_NOTICES.md, en el repositorio, y la propia app lo muestra en Más, luego Avisos de terceros. Este sitio repite las partes que importan para leer el código.',
    },
    build: {
      title: 'Compílalo tú mismo',
      body: 'El proyecto compila con el wrapper de Gradle que ya está en el repositorio. Necesitas un toolchain local; nada se descarga de un servidor de Vezuna.',
      steps: [
        { title: 'Instala el toolchain', body: 'JDK 17, Android SDK API 37 con minSdk 26 y Python 3. La app de iOS necesita macOS con Xcode y un deployment target de 18.5.' },
        { title: 'Compila y ejecuta las pruebas', body: './gradlew :shared:jvmTest :composeApp:assembleDebug :composeApp:testDebugUnitTest :composeApp:assembleDebugAndroidTest construye el módulo compartido, la app de Android y el APK de pruebas.' },
        { title: 'Ejecuta las comprobaciones sintéticas', body: 'python3 tools/lab_importer/test_importer.py, python3 tools/localization/check_localization.py y python3 tools/branding/check_brand.py revisan los fixtures, los catálogos de los tres idiomas y los archivos de marca.' },
        { title: 'Construye el sitio', body: 'Dentro de website/, npm ci y luego npm run build producen el sitio estático que estás leyendo. El sitio no usa cuenta, analítica ni servidor.' },
      ],
      cta: 'Abrir la guía del desarrollador (en inglés)',
      commands: {
        caption: 'Los mismos comandos funcionan en un checkout limpio, desde la raíz del repositorio.',
        lines: [
          './gradlew :shared:jvmTest :composeApp:assembleDebug',
          'python3 tools/lab_importer/test_importer.py',
          'python3 tools/localization/check_localization.py',
          'cd website && npm ci && npm run build',
        ],
      },
    },
    contribute: {
      title: 'Cómo contribuir',
      body: 'El proyecto se mantiene en abierto. Lee las reglas de contribución antes de empezar y mantén un cambio por pull request.',
      items: [
        { term: 'Abre un issue antes', body: 'Los cambios grandes empiezan como issue, para acordar el diseño antes de escribir el código.' },
        { term: 'Sigue la estructura existente', body: 'La lógica compartida va en shared/commonMain. El código de plataforma va detrás de expect/actual o de los puentes ya existentes.' },
        { term: 'Traduce cada texto', body: 'El texto visible vive en los catálogos de inglés, portugués y español. tools/localization/check_localization.py comprueba la paridad.' },
        { term: 'Usa solo datos sintéticos', body: 'Nunca subas datos de salud reales. Informes, recetas, nombres y capturas en issues y pull requests tienen que ser ficticios.' },
        { term: 'Mantén el changelog honesto', body: 'Los cambios visibles van en Unreleased dentro de CHANGELOG.md, y el pull request dice qué comprobaciones se ejecutaron y cuáles no.' },
        { term: 'Informa de vulnerabilidades en privado', body: 'Los problemas de seguridad van por la pestaña Security del repositorio, nunca por un issue público.' },
      ],
      cta: 'Leer el CONTRIBUTING.md',
    },
    status: {
      title: 'En qué punto está el proyecto',
      body: 'Ser código abierto es un hecho sobre la licencia. No es una afirmación de que exista una app terminada. Estos puntos siguen abiertos:',
      items: [
        'No se ha publicado ninguna versión estable. Las compilaciones firmadas y la revisión de las tiendas siguen pendientes, así que no hay enlaces de descarga.',
        'El catálogo de modelos en producción está vacío, así que no hay ninguna función de IA disponible. El código de inferencia está en el repositorio, pero no hay ningún paquete de modelo configurado ni entregado.',
        'Las obligaciones de atribución y redistribución de las bibliotecas nativas precompiladas siguen sin resolverse y son una pendiente de publicación.',
        'Faltan las comprobaciones en dispositivo físico para permisos, hora de las notificaciones, copias de seguridad, exportaciones y lectura de Health Connect y Apple Salud.',
        'La app guarda y organiza registros de salud. No diagnostica, no recomienda tratamientos y no cambia dosis.',
      ],
    },
    next: {
      title: 'Adónde ir ahora',
      body: 'El código, los issues, las reglas de contribución, la licencia y el aviso completo de terceros están a un clic.',
    },
    links: {
      repository: 'Repositorio del código',
      issues: 'Issues',
      security: 'Informar de una vulnerabilidad',
      contributing: 'CONTRIBUTING.md',
      notices: 'THIRD_PARTY_NOTICES.md',
      developers: 'Guía del desarrollador',
      license: 'Licencia MIT',
      guides: 'Guía de desarrollo en el repositorio',
      repositoryNotices: 'Leer el aviso completo en el repositorio',
    },
  },
};

export const screensCopy: Record<Locale, ScreensCopy> = {
  en: {
    meta: {
      title: 'Screens',
      description: 'Real captures of the Vezuna Android app with fictional records, plus exactly what these screenshots do and do not prove.',
    },
    hero: {
      eyebrow: 'Screens',
      title: 'Every capture on this page came from the real app.',
      body: 'These are emulator captures of the Compose interface, with a fictional household and synthetic records. Nothing here is a mock-up drawn for a website, and nothing shows a published store build.',
    },
    gallery: {
      title: 'Three screens, and what each one does',
      body: 'The interface ships in English, Brazilian Portuguese, and Spanish. The captures below were taken with the interface in English, so the labels you see are the English ones.',
    },
    limits: {
      title: 'What these captures do not prove',
      body: 'Screenshots are evidence of an interface, not of a release. These limits are part of the picture.',
      items: [
        'They are Android captures from a development build. They are not evidence of iPhone runtime behavior, and a successful simulator build does not establish it.',
        'They are not signed store builds. No stable release has been published, and store review is still open.',
        'They show records for a fictional profile. No real person, report, or prescription appears in any of them.',
        'They show no AI feature, because the production model catalog is empty and no model pack is delivered.',
        'They say nothing about clinical accuracy. Vezuna stores what you confirm; it does not interpret values.',
      ],
    },
    cta: {
      title: 'Read the source, or read the guide',
      body: 'The whole app source is MIT licensed and public. The documentation explains what each screen does with the records it holds.',
      primary: 'Read the getting-started guide',
      secondary: 'Open the source repository',
    },
  },
  'pt-br': {
    meta: {
      title: 'Telas',
      description: 'Capturas reais do app Vezuna no Android com registros fictícios, e exatamente o que essas capturas provam e não provam.',
    },
    hero: {
      eyebrow: 'Telas',
      title: 'Todas as capturas desta página saíram do app real.',
      body: 'São capturas de emulador da interface Compose, com uma família fictícia e registros sintéticos. Nada aqui é um desenho feito para o site, e nada mostra uma versão publicada na loja.',
    },
    gallery: {
      title: 'Três telas e o que cada uma faz',
      body: 'A interface sai em inglês, português do Brasil e espanhol. As capturas abaixo foram feitas com a interface em inglês, então os rótulos são os em inglês.',
    },
    limits: {
      title: 'O que estas capturas não provam',
      body: 'Capturas de tela são evidência de uma interface, não de um release. Estes limites fazem parte do quadro.',
      items: [
        'São capturas de Android de uma build de desenvolvimento. Não provam o comportamento em runtime no iPhone, e uma build de simulador bem-sucedida não estabelece isso.',
        'Não são builds assinados de loja. Nenhuma versão estável foi publicada, e a análise das lojas continua aberta.',
        'Mostram registros de um perfil fictício. Nenhuma pessoa, laudo ou prescrição real aparece nelas.',
        'Não mostram recurso de IA, porque o catálogo de modelos em produção está vazio e nenhum pacote é distribuído.',
        'Não dizem nada sobre acurácia clínica. O Vezuna guarda o que você confirma; ele não interpreta valores.',
      ],
    },
    cta: {
      title: 'Leia o código ou leia o guia',
      body: 'Todo o código do app é MIT e está público. A documentação explica o que cada tela faz com os registros que guarda.',
      primary: 'Ler o guia de primeiros passos',
      secondary: 'Abrir o repositório',
    },
  },
  es: {
    meta: {
      title: 'Pantallas',
      description: 'Capturas reales de la app Vezuna en Android con registros ficticios, y exactamente qué demuestran y qué no.',
    },
    hero: {
      eyebrow: 'Pantallas',
      title: 'Todas las capturas de esta página salen de la app real.',
      body: 'Son capturas de emulador de la interfaz Compose, con una familia ficticia y registros sintéticos. Nada de esto es un dibujo hecho para el sitio, y nada muestra una versión publicada en tienda.',
    },
    gallery: {
      title: 'Tres pantallas y qué hace cada una',
      body: 'La interfaz se distribuye en inglés, portugués de Brasil y español. Las capturas se hicieron con la interfaz en inglés, así que las etiquetas son las inglesas.',
    },
    limits: {
      title: 'Lo que estas capturas no demuestran',
      body: 'Las capturas son evidencia de una interfaz, no de una publicación. Estos límites forman parte del cuadro.',
      items: [
        'Son capturas de Android de una compilación de desarrollo. No demuestran el comportamiento en runtime en iPhone, y una compilación de simulador correcta no lo establece.',
        'No son compilaciones firmadas de tienda. No se ha publicado ninguna versión estable, y la revisión de tiendas sigue abierta.',
        'Muestran registros de un perfil ficticio. Ninguna persona, informe o receta real aparece en ellas.',
        'No muestran ninguna función de IA, porque el catálogo de modelos en producción está vacío y no se entrega ningún paquete.',
        'No dicen nada sobre exactitud clínica. Vezuna guarda lo que confirmas; no interpreta valores.',
      ],
    },
    cta: {
      title: 'Lee el código o lee la guía',
      body: 'Todo el código de la app es MIT y está público. La documentación explica qué hace cada pantalla con los registros que guarda.',
      primary: 'Leer la guía de inicio',
      secondary: 'Abrir el repositorio',
    },
  },
};

export const howItWorksCopy: Record<Locale, HowItWorksCopy> = {
  en: {
    meta: {
      title: 'How it works',
      description: 'From a paper lab report to a confirmed value, a reminder, an export, and a restorable backup. Step by step, without hidden steps.',
    },
    hero: {
      eyebrow: 'How it works',
      title: 'A report becomes a record only after you check it.',
      body: 'Text recognition makes mistakes, and a health record is worth little if the value in it was read wrong. Vezuna treats everything it reads as a draft and makes you the reviewer.',
    },
    flow: {
      title: 'The four steps',
      body: 'The same path runs for every lab report, on Android and on iPhone.',
      steps: [
        { title: 'Bring the report in', body: 'Use the camera, pick an image, or open a PDF. Text is read on the phone; a PDF with embedded text is read directly, and an image falls back to on-device OCR.' },
        { title: 'Check the draft', body: 'Vezuna shows the test name, value, unit, reference range, and date it read. Every field is editable. A value that looks ambiguous, such as a dot that may mean thousands, is flagged for you rather than guessed.' },
        { title: 'Confirm it', body: 'Only confirmed results become part of the person’s history. Until then the draft is not in the record, and it does not appear in comparisons or exports.' },
        { title: 'Compare over time', body: 'Confirmed results are graphed together only when their units match. A value in mg/dL is never drawn on the same axis as one in g/dL.' },
      ],
    },
    reminders: {
      title: 'Medication reminders',
      body: 'Each person has their own schedule, and each dose gets its own answer.',
      items: [
        { term: 'Per profile', body: 'Schedules belong to one person. The reminder list always shows who a dose belongs to.' },
        { term: 'Three answers', body: 'Mark a dose as taken, skipped, or snoozed. Snoozing pushes it back by ten minutes.' },
        { term: 'Adherence', body: 'The Today screen shows recorded doses marked taken out of recorded completed doses over the last thirty days.' },
        { term: 'Local notifications', body: 'Reminders are scheduled on the device. Notification permission is optional, and the schedule itself stays on the phone either way.' },
      ],
    },
    exports: {
      title: 'Exports for an appointment',
      body: 'Confirmed results can leave the vault as a PDF or a CSV, on your terms.',
      items: [
        { term: 'Confirmed only', body: 'An export contains confirmed results, not drafts. The reference range printed on the original report travels with the value.' },
        { term: 'Your share sheet', body: 'The file goes out through the phone’s own share sheet, so you choose where it goes: email, cloud storage, a messaging app, or printing.' },
        { term: 'Language of the export', body: 'Headings, statuses, and number formats follow the language you pick for the export. Clinical values and units never change with the language.' },
        { term: 'No model involved', body: 'An export never depends on an AI model. It works with the catalog empty, exactly like every other part of the app.' },
      ],
    },
    backups: {
      title: 'Backups and moving to a new phone',
      body: 'Records live on one phone. A backup is the only way to move them.',
      items: [
        { term: 'One passphrase', body: 'You choose a passphrase of at least twelve characters. It is the only thing that opens the backup file.' },
        { term: 'No recovery', body: 'Vezuna cannot recover a lost passphrase. If it is lost, the backup file can never be opened.' },
        { term: 'Two kinds', body: 'A backup of one profile carries that profile and its vitals. A backup of the whole household also carries the original report files and stored documents, up to 24 MB.' },
        { term: 'Restore anywhere', body: 'The backup file is portable. Open it on a new phone with the same passphrase, after the app is installed there.' },
      ],
    },
    controls: {
      title: 'Controls on the device',
      body: 'The More screen groups everything that is about the device rather than about a person.',
      items: [
        { term: 'App lock', body: 'Ask for biometrics or the device passcode when Vezuna opens, and again after five minutes in the background. On Android, app lock blocks screenshots. On iOS, the app covers its content when the scene is inactive; this does not block screenshots taken while the app is active.' },
        { term: 'Device connections', body: 'Health Connect on Android and Apple Health on iPhone are read-only, and each one is off until you allow it.' },
        { term: 'Profiles', body: 'Every person in the household has a separate profile with a separate vault. Records never mix between profiles.' },
        { term: 'Third-party notices', body: 'The app carries the license texts for its own dependencies, readable offline under More, then Third-party notices.' },
      ],
    },
    never: {
      title: 'What Vezuna never does',
      body: 'The limits are as much a part of the product as the features.',
      items: [
        'It does not diagnose a condition, recommend a treatment, or change a dose.',
        'It does not read a value and store it without you checking it.',
        'It does not need an account, and it has no Vezuna server.',
        'It does not contain ads, analytics, or telemetry.',
        'It does not add a network client for health data: the Android manifest removes the INTERNET permission from the merged manifest.',
        'It does not ship an AI model. The production model catalog is empty, so no AI feature is available.',
      ],
    },
    links: {
      localData: 'Read where the data lives',
      openSource: 'Read the open source page',
      screens: 'See the real screens',
    },
  },
  'pt-br': {
    meta: {
      title: 'Como funciona',
      description: 'Do laudo em papel até o valor confirmado, o lembrete, a exportação e o backup que dá para restaurar. Passo a passo, sem etapas escondidas.',
    },
    hero: {
      eyebrow: 'Como funciona',
      title: 'Um laudo só vira registro depois que você confere.',
      body: 'O reconhecimento de texto erra, e um registro de saúde vale pouco se o valor foi lido errado. O Vezuna trata tudo que lê como rascunho e deixa você ser o revisor.',
    },
    flow: {
      title: 'Os quatro passos',
      body: 'O mesmo caminho vale para todo laudo, no Android e no iPhone.',
      steps: [
        { title: 'Traga o laudo', body: 'Use a câmera, escolha uma imagem ou abra um PDF. O texto é lido no celular; PDF com texto embutido é lido direto, e imagem usa o OCR no aparelho.' },
        { title: 'Confira o rascunho', body: 'O Vezuna mostra o nome do exame, o valor, a unidade, o intervalo de referência e a data que leu. Todo campo é editável. Valor ambíguo, como um ponto que pode ser milhar, é sinalizado em vez de adivinhado.' },
        { title: 'Confirme', body: 'Só resultados confirmados entram no histórico da pessoa. Até lá o rascunho não está no registro, e não aparece em comparações nem em exportações.' },
        { title: 'Compare ao longo do tempo', body: 'Resultados confirmados entram no gráfico só quando as unidades batem. Valor em mg/dL nunca é desenhado no mesmo eixo que outro em g/dL.' },
      ],
    },
    reminders: {
      title: 'Lembretes de medicamentos',
      body: 'Cada pessoa tem o próprio horário, e cada dose tem a própria resposta.',
      items: [
        { term: 'Por perfil', body: 'Os horários pertencem a uma pessoa. A lista de lembretes mostra sempre de quem é a dose.' },
        { term: 'Três respostas', body: 'Marque a dose como tomada, pulada ou adiada. Adiar empurra a dose dez minutos.' },
        { term: 'Adesão', body: 'A tela Today mostra as doses registradas como tomadas em relação às doses com registro concluído nos últimos trinta dias.' },
        { term: 'Notificações locais', body: 'Os lembretes são agendados no aparelho. A permissão de notificação é opcional, e a agenda fica no celular de qualquer forma.' },
      ],
    },
    exports: {
      title: 'Exportações para a consulta',
      body: 'Resultados confirmados podem sair do cofre em PDF ou CSV, do jeito que você quiser.',
      items: [
        { term: 'Só confirmados', body: 'A exportação leva resultados confirmados, não rascunhos. O intervalo de referência impresso no laudo original viaja junto com o valor.' },
        { term: 'Seu compartilhamento', body: 'O arquivo sai pelo menu de compartilhamento do próprio celular, então você escolhe o destino: e-mail, nuvem, aplicativo de mensagens ou impressão.' },
        { term: 'Idioma da exportação', body: 'Títulos, estados e formato de número seguem o idioma escolhido para a exportação. Valores clínicos e unidades nunca mudam com o idioma.' },
        { term: 'Sem modelo envolvido', body: 'A exportação nunca depende de um modelo de IA. Funciona com o catálogo vazio, igual a qualquer outra parte do app.' },
      ],
    },
    backups: {
      title: 'Backups e mudança de celular',
      body: 'Os registros vivem em um celular. O backup é o único jeito de levá-los junto.',
      items: [
        { term: 'Uma frase-senha', body: 'Você escolhe uma frase-senha de pelo menos doze caracteres. É a única coisa que abre o arquivo de backup.' },
        { term: 'Sem recuperação', body: 'O Vezuna não recupera frase-senha perdida. Se ela se perde, o arquivo de backup nunca mais pode ser aberto.' },
        { term: 'Dois tipos', body: 'O backup de um perfil leva aquele perfil e seus sinais vitais. O backup da família toda também leva os arquivos originais dos laudos e os documentos guardados, até 24 MB.' },
        { term: 'Restaure em qualquer lugar', body: 'O arquivo de backup é portátil. Abra no celular novo com a mesma frase-senha, depois de instalar o app lá.' },
      ],
    },
    controls: {
      title: 'Controles no aparelho',
      body: 'A tela More reúne o que é do aparelho, e não de uma pessoa.',
      items: [
        { term: 'Bloqueio do app', body: 'Peça biometria ou a senha do aparelho quando o Vezuna abre, e de novo depois de cinco minutos em segundo plano. No Android, o bloqueio do app impede capturas de tela. No iOS, o app cobre o conteúdo quando a cena está inativa; isso não bloqueia capturas enquanto o app está ativo.' },
        { term: 'Conexões com o aparelho', body: 'Health Connect no Android e Apple Saúde no iPhone são só de leitura, e cada um fica desligado até você permitir.' },
        { term: 'Perfis', body: 'Cada pessoa da família tem um perfil e um cofre separados. Os registros nunca se misturam entre perfis.' },
        { term: 'Avisos de terceiros', body: 'O app traz os textos de licença das próprias dependências, legíveis offline em Mais, depois Avisos de terceiros.' },
      ],
    },
    never: {
      title: 'O que o Vezuna nunca faz',
      body: 'Os limites fazem parte do produto tanto quanto as funções.',
      items: [
        'Ele não diagnostica, não recomenda tratamento e não muda dose.',
        'Ele não lê um valor e salva sem você conferir.',
        'Ele não exige conta e não tem servidor do Vezuna.',
        'Ele não tem anúncios, analytics nem telemetria.',
        'Ele não adiciona cliente de rede para dados de saúde: o manifesto do Android remove a permissão INTERNET do manifesto final.',
        'Ele não traz modelo de IA. O catálogo de modelos em produção está vazio, então nenhum recurso de IA está disponível.',
      ],
    },
    links: {
      localData: 'Ver onde ficam os dados',
      openSource: 'Ver a página de código aberto',
      screens: 'Ver as telas reais',
    },
  },
  es: {
    meta: {
      title: 'Cómo funciona',
      description: 'Del informe en papel al valor confirmado, el recordatorio, la exportación y la copia que se puede restaurar. Paso a paso, sin pasos ocultos.',
    },
    hero: {
      eyebrow: 'Cómo funciona',
      title: 'Un informe se vuelve registro solo después de que lo revisas.',
      body: 'El reconocimiento de texto se equivoca, y un registro de salud vale poco si el valor se leyó mal. Vezuna trata todo lo que lee como un borrador y te deja revisar.',
    },
    flow: {
      title: 'Los cuatro pasos',
      body: 'El mismo camino sirve para cada informe, en Android y en iPhone.',
      steps: [
        { title: 'Trae el informe', body: 'Usa la cámara, elige una imagen o abre un PDF. El texto se lee en el teléfono; un PDF con texto incrustado se lee directamente, y una imagen usa el OCR en el dispositivo.' },
        { title: 'Revisa el borrador', body: 'Vezuna muestra la prueba, el valor, la unidad, el rango de referencia y la fecha que leyó. Todos los campos son editables. Un valor ambiguo, como un punto que puede ser millar, se marca en vez de adivinarse.' },
        { title: 'Confírmalo', body: 'Solo los resultados confirmados pasan al historial de la persona. Hasta entonces el borrador no está en el registro, y no aparece en comparaciones ni en exportaciones.' },
        { title: 'Compara a lo largo del tiempo', body: 'Los resultados confirmados se grafican juntos solo cuando sus unidades coinciden. Un valor en mg/dL nunca se dibuja en el mismo eje que otro en g/dL.' },
      ],
    },
    reminders: {
      title: 'Recordatorios de medicamentos',
      body: 'Cada persona tiene su propio horario, y cada dosis tiene su propia respuesta.',
      items: [
        { term: 'Por perfil', body: 'Los horarios pertenecen a una persona. La lista de recordatorios muestra siempre de quién es la dosis.' },
        { term: 'Tres respuestas', body: 'Marca la dosis como tomada, omitida o pospuesta. Posponer la retrasa diez minutos.' },
        { term: 'Adherencia', body: 'La pantalla Today muestra las dosis registradas como tomadas respecto a las dosis con registro completado en los últimos treinta días.' },
        { term: 'Notificaciones locales', body: 'Los recordatorios se programan en el dispositivo. El permiso de notificaciones es opcional, y la agenda se queda en el teléfono igual.' },
      ],
    },
    exports: {
      title: 'Exportaciones para la consulta',
      body: 'Los resultados confirmados pueden salir del almacén en PDF o CSV, como tú decidas.',
      items: [
        { term: 'Solo confirmados', body: 'Una exportación lleva resultados confirmados, no borradores. El rango de referencia impreso en el informe original acompaña al valor.' },
        { term: 'Tu menú de compartir', body: 'El archivo sale por el menú de compartir del propio teléfono, así que eliges el destino: correo, nube, aplicación de mensajes o impresión.' },
        { term: 'Idioma de la exportación', body: 'Títulos, estados y formato de números siguen el idioma que elijas para la exportación. Los valores clínicos y las unidades nunca cambian con el idioma.' },
        { term: 'Ningún modelo de por medio', body: 'Una exportación nunca depende de un modelo de IA. Funciona con el catálogo vacío, igual que todo lo demás.' },
      ],
    },
    backups: {
      title: 'Copias de seguridad y cambio de teléfono',
      body: 'Los registros viven en un teléfono. Una copia es la única forma de llevárselos.',
      items: [
        { term: 'Una frase de contraseña', body: 'Eliges una frase de contraseña de al menos doce caracteres. Es lo único que abre el archivo de copia.' },
        { term: 'Sin recuperación', body: 'Vezuna no recupera una frase perdida. Si se pierde, el archivo de copia no podrá abrirse nunca más.' },
        { term: 'Dos clases', body: 'Una copia de un perfil lleva ese perfil y sus signos vitales. Una copia de toda la familia lleva además los archivos originales de los informes y los documentos guardados, hasta 24 MB.' },
        { term: 'Restáuralo en cualquier sitio', body: 'El archivo de copia es portátil. Ábrelo en un teléfono nuevo con la misma frase, después de instalar la app allí.' },
      ],
    },
    controls: {
      title: 'Controles en el dispositivo',
      body: 'La pantalla More reúne lo que es del dispositivo y no de una persona.',
      items: [
        { term: 'Bloqueo de la app', body: 'Pide biometría o el código del dispositivo cuando Vezuna se abre, y otra vez tras cinco minutos en segundo plano. En Android, el bloqueo de la app impide capturas de pantalla. En iOS, la app cubre el contenido cuando la escena está inactiva; esto no bloquea capturas mientras la app está activa.' },
        { term: 'Conexiones con el dispositivo', body: 'Health Connect en Android y Apple Salud en iPhone son de solo lectura, y cada uno queda desactivado hasta que lo permites.' },
        { term: 'Perfiles', body: 'Cada persona de la familia tiene un perfil y un almacén separados. Los registros nunca se mezclan entre perfiles.' },
        { term: 'Avisos de terceros', body: 'La app lleva los textos de licencia de sus propias dependencias, legibles sin conexión en Más, luego Avisos de terceros.' },
      ],
    },
    never: {
      title: 'Lo que Vezuna nunca hace',
      body: 'Los límites son parte del producto tanto como las funciones.',
      items: [
        'No diagnostica una condición, no recomienda un tratamiento y no cambia una dosis.',
        'No lee un valor y lo guarda sin que tú lo revises.',
        'No pide cuenta y no tiene servidor de Vezuna.',
        'No tiene anuncios, analítica ni telemetría.',
        'No añade un cliente de red para datos de salud: el manifiesto de Android quita el permiso INTERNET del manifiesto final.',
        'No incluye un modelo de IA. El catálogo de modelos en producción está vacío, así que no hay ninguna función de IA disponible.',
      ],
    },
    links: {
      localData: 'Leer dónde viven los datos',
      openSource: 'Leer la página de código abierto',
      screens: 'Ver las pantallas reales',
    },
  },
};

export const localDataCopy: Record<Locale, LocalDataCopy> = {
  en: {
    meta: {
      title: 'Local data',
      description: 'Where each kind of record is stored on the phone, which keys protect it, which permissions are optional, and how to remove it.',
    },
    hero: {
      eyebrow: 'Local data',
      title: 'Your records live in one encrypted vault on this phone.',
      body: 'There is no Vezuna account and no Vezuna server. Each profile has its own vault inside the app’s private storage, and the phone is the only place your records exist until you decide otherwise.',
    },
    map: {
      title: 'What sits where',
      body: 'The same list appears on the product page. It is the shortest honest answer to “where does my data go”.',
      columns: { what: 'Data', where: 'Where it stays', leaves: 'When it leaves the phone' },
      rows: [
        { what: 'Health records', where: 'An encrypted vault in the app’s private storage, separated by profile', leaves: 'Only when you export or back up' },
        { what: 'PDF and CSV exports', where: 'A plain file you create on request', leaves: 'When you share or save it. From then on it is outside the vault' },
        { what: 'Backups', where: 'A file encrypted with your passphrase', leaves: 'Wherever you save it. Without the passphrase nobody can open it, including the maintainers' },
        { what: 'Health Connect and Apple Health', where: 'Read with your permission, never written', leaves: 'Never sent anywhere' },
        { what: 'Original report files', where: 'App-private storage, attached to the profile that imported them', leaves: 'Only inside a backup, or when you delete them' },
        { what: 'Reminders', where: 'Scheduled locally on the device', leaves: 'Never. They are not sent to a push service' },
        { what: 'Purchase', where: 'Handled by the App Store or Google Play', leaves: 'The store sees the purchase, never your records' },
      ],
    },
    keys: {
      title: 'What protects it',
      body: 'Exact statements about the implementation, so you can check them against the code.',
      items: [
        { term: 'Android vault', body: 'A non-exportable AES-256 key in Android Keystore, with AES-GCM encryption.' },
        { term: 'iOS vault', body: 'CryptoKit AES-GCM with protected app-private files. The vault key is stored in the device-only Keychain, accessible after the first unlock.' },
        { term: 'Backup file', body: 'Encrypted with the passphrase you choose, of at least twelve characters. There is no recovery path for a lost passphrase.' },
        { term: 'App lock', body: 'Biometrics or the device passcode when Vezuna opens, and after five minutes in the background. On Android, app lock blocks screenshots. On iOS, the app covers its content when the scene is inactive; this does not block screenshots taken while the app is active.' },
        { term: 'What is not claimed', body: 'No independent security audit has been performed. The statements above describe the implementation, not a third-party attestation.' },
      ],
    },
    permissions: {
      title: 'Permissions, and what happens without them',
      body: 'Every permission is optional, and each one is independent of the others.',
      items: [
        { term: 'Camera and selected photos', body: 'Needed to import a report from a picture. Records you type by hand work without it.' },
        { term: 'Notifications', body: 'Needed to show medication reminders. The schedule still exists on the phone when permission is denied.' },
        { term: 'Health Connect or Apple Health', body: 'Read-only access to heart rate, steps, and weight. Without it, you enter those values yourself.' },
        { term: 'What is never asked for', body: 'There is no network client for health data. On Android, the merged manifest keeps the INTERNET permission removed.' },
      ],
    },
    deletion: {
      title: 'Removing your data',
      body: 'Deletion is a normal operation on the device, and it has limits worth knowing.',
      items: [
        { term: 'Inside the app', body: 'Deleting a record removes it from the active records. On Android, a previous encrypted generation may remain for recovery; damaged files may also be retained in quarantine. Profile deletion uses a separate removal path. Deletion is not a guarantee of immediate removal of every local copy or secure erasure.' },
        { term: 'Outside the app', body: 'A file you already exported, shared, or saved as a backup is no longer under the app’s control. Delete it where you put it.' },
        { term: 'Removing the app', body: 'Uninstalling removes the app’s private storage. Any backup file you saved elsewhere stays where it is.' },
        { term: 'What cannot be deleted', body: 'Nothing is kept on a server, so there is nothing to request the deletion of. This is the practical advantage of having no account.' },
      ],
    },
    site: {
      title: 'This website',
      body: 'This site is a static build. It sets no cookies, runs no analytics, loads no third-party scripts, and sends nothing about you to an advertising or tracking service. The site is hosted on Vercel, which processes the request data any web host does. The only outbound links are to the public source repository and to the documentation.',
    },
    audit: {
      note: 'No independent security audit has been done yet, and this site makes no claim of regulatory certification. The implementation statements above are the ones the code supports.',
    },
    links: {
      policy: 'Read the privacy policy',
      details: 'Read the technical privacy details',
      source: 'Read the source',
    },
  },
  'pt-br': {
    meta: {
      title: 'Dados locais',
      description: 'Onde cada tipo de registro fica guardado no celular, quais chaves protegem, quais permissões são opcionais e como remover.',
    },
    hero: {
      eyebrow: 'Dados locais',
      title: 'Seus registros ficam em um cofre criptografado neste celular.',
      body: 'Não existe conta do Vezuna nem servidor do Vezuna. Cada perfil tem um cofre próprio dentro do armazenamento privado do app, e o celular é o único lugar onde seus registros existem até você decidir outra coisa.',
    },
    map: {
      title: 'O que fica onde',
      body: 'A mesma lista aparece na página do produto. É a resposta mais curta e honesta para “onde ficam meus dados”.',
      columns: { what: 'Dado', where: 'Onde fica', leaves: 'Quando sai do celular' },
      rows: [
        { what: 'Registros de saúde', where: 'Um cofre criptografado no armazenamento privado do app, separado por perfil', leaves: 'Só quando você exporta ou faz backup' },
        { what: 'Exportações em PDF e CSV', where: 'Um arquivo comum que você cria quando pede', leaves: 'Quando você compartilha ou salva. A partir daí, fica fora do cofre' },
        { what: 'Backups', where: 'Um arquivo criptografado com a sua frase-senha', leaves: 'Onde você salvar. Sem a frase-senha ninguém abre, nem quem mantém o projeto' },
        { what: 'Health Connect e Apple Saúde', where: 'Lidos com a sua permissão, nunca alterados', leaves: 'Nunca são enviados' },
        { what: 'Arquivos originais dos laudos', where: 'Armazenamento privado do app, ligados ao perfil que os importou', leaves: 'Só dentro de um backup, ou quando você os apaga' },
        { what: 'Lembretes', where: 'Agendados localmente no aparelho', leaves: 'Nunca. Não vão para nenhum serviço de push' },
        { what: 'Compra', where: 'Feita pela App Store ou pelo Google Play', leaves: 'A loja vê a compra, nunca os registros' },
      ],
    },
    keys: {
      title: 'O que protege',
      body: 'Declarações exatas sobre a implementação, para você conferir no código.',
      items: [
        { term: 'Cofre no Android', body: 'Uma chave AES-256 não exportável no Android Keystore, com criptografia AES-GCM.' },
        { term: 'Cofre no iOS', body: 'CryptoKit AES-GCM com arquivos protegidos no armazenamento privado do app. A chave do cofre fica no Keychain, restrita a este aparelho e acessível após o primeiro desbloqueio.' },
        { term: 'Arquivo de backup', body: 'Criptografado com a frase-senha que você escolhe, de pelo menos doze caracteres. Frase-senha perdida não tem caminho de recuperação.' },
        { term: 'Bloqueio do app', body: 'Biometria ou a senha do aparelho quando o Vezuna abre, e depois de cinco minutos em segundo plano. No Android, o bloqueio do app impede capturas de tela. No iOS, o app cobre o conteúdo quando a cena está inativa; isso não bloqueia capturas enquanto o app está ativo.' },
        { term: 'O que não é alegado', body: 'Não houve auditoria de segurança independente. As afirmações acima descrevem a implementação, não um atestado de terceiro.' },
      ],
    },
    permissions: {
      title: 'Permissões e o que acontece sem elas',
      body: 'Toda permissão é opcional, e cada uma é independente das outras.',
      items: [
        { term: 'Câmera e fotos selecionadas', body: 'Necessárias para importar um laudo de foto. Registros digitados à mão funcionam sem elas.' },
        { term: 'Notificações', body: 'Necessárias para mostrar lembretes de medicamentos. A agenda continua no celular quando a permissão é negada.' },
        { term: 'Health Connect ou Apple Saúde', body: 'Leitura de frequência cardíaca, passos e peso. Sem ela, você digita esses valores.' },
        { term: 'O que nunca é pedido', body: 'Não existe cliente de rede para dados de saúde. No Android, o manifesto final mantém a permissão INTERNET removida.' },
      ],
    },
    deletion: {
      title: 'Como remover seus dados',
      body: 'Apagar é uma operação normal no aparelho, e vale conhecer os limites.',
      items: [
        { term: 'Dentro do app', body: 'Apagar um registro o remove dos registros ativos. No Android, uma geração anterior criptografada pode permanecer para recuperação; arquivos danificados também podem ficar em quarentena. A exclusão de perfil usa um caminho separado. Apagar não garante a remoção imediata de todas as cópias locais nem apagamento seguro.' },
        { term: 'Fora do app', body: 'Um arquivo que você já exportou, compartilhou ou salvou como backup não está mais sob controle do app. Apague onde você guardou.' },
        { term: 'Removendo o app', body: 'Desinstalar remove o armazenamento privado do app. Qualquer backup salvo em outro lugar continua onde está.' },
        { term: 'O que não pode ser apagado', body: 'Nada é guardado em servidor, então não há o que pedir para apagar. É a vantagem prática de não ter conta.' },
      ],
    },
    site: {
      title: 'Este site',
      body: 'Este site é um build estático. Ele não usa cookies, não roda analytics, não carrega scripts de terceiros e não envia nada sobre você para serviço de publicidade ou rastreamento. O site está hospedado na Vercel, que processa os dados de requisição como qualquer hospedagem web. Os únicos links externos vão para o repositório público e para a documentação.',
    },
    audit: {
      note: 'Ainda não houve auditoria de segurança independente, e este site não alega certificação regulatória. As declarações de implementação acima são as que o código sustenta.',
    },
    links: {
      policy: 'Ler a política de privacidade',
      details: 'Ler os detalhes técnicos de privacidade',
      source: 'Ler o código',
    },
  },
  es: {
    meta: {
      title: 'Datos locales',
      description: 'Dónde se guarda cada tipo de registro en el teléfono, qué claves lo protegen, qué permisos son opcionales y cómo eliminarlo.',
    },
    hero: {
      eyebrow: 'Datos locales',
      title: 'Tus registros viven en un almacén cifrado en este teléfono.',
      body: 'No hay cuenta de Vezuna ni servidor de Vezuna. Cada perfil tiene su propio almacén dentro del almacenamiento privado de la app, y el teléfono es el único lugar donde existen tus registros hasta que tú decidas otra cosa.',
    },
    map: {
      title: 'Qué queda dónde',
      body: 'La misma lista aparece en la página del producto. Es la respuesta más corta y honesta a «dónde están mis datos».',
      columns: { what: 'Dato', where: 'Dónde se queda', leaves: 'Cuándo sale del teléfono' },
      rows: [
        { what: 'Registros de salud', where: 'En un almacén cifrado en el almacenamiento privado de la app, separado por perfil', leaves: 'Solo cuando exportas o haces una copia' },
        { what: 'Exportaciones PDF y CSV', where: 'Un archivo normal que creas cuando pides', leaves: 'Cuando lo compartes o lo guardas. Desde entonces está fuera del almacén' },
        { what: 'Copias de seguridad', where: 'Un archivo cifrado con tu frase de contraseña', leaves: 'Donde lo guardes. Sin la frase no puede abrirlo nadie, ni el mantenimiento' },
        { what: 'Health Connect y Apple Salud', where: 'Se leen con tu permiso, nunca se modifican', leaves: 'Nunca se envían' },
        { what: 'Archivos originales de los informes', where: 'Almacenamiento privado de la app, unidos al perfil que los importó', leaves: 'Solo dentro de una copia, o cuando los borras' },
        { what: 'Recordatorios', where: 'Programados localmente en el dispositivo', leaves: 'Nunca. No van a ningún servicio de push' },
        { what: 'Compra', where: 'La gestiona App Store o Google Play', leaves: 'La tienda ve la compra, nunca tus registros' },
      ],
    },
    keys: {
      title: 'Qué lo protege',
      body: 'Declaraciones exactas sobre la implementación, para que puedas comprobarlas en el código.',
      items: [
        { term: 'Almacén en Android', body: 'Una clave AES-256 no exportable en Android Keystore, con cifrado AES-GCM.' },
        { term: 'Almacén en iOS', body: 'CryptoKit AES-GCM con archivos protegidos en el almacenamiento privado de la app. La clave del almacén se guarda en el Keychain, solo para este dispositivo y accesible tras el primer desbloqueo.' },
        { term: 'Archivo de copia', body: 'Cifrado con la frase que eliges, de al menos doce caracteres. Una frase perdida no tiene camino de recuperación.' },
        { term: 'Bloqueo de la app', body: 'Biometría o el código del dispositivo cuando Vezuna se abre, y tras cinco minutos en segundo plano. En Android, el bloqueo de la app impide capturas de pantalla. En iOS, la app cubre el contenido cuando la escena está inactiva; esto no bloquea capturas mientras la app está activa.' },
        { term: 'Lo que no se afirma', body: 'No se ha hecho una auditoría de seguridad independiente. Las afirmaciones anteriores describen la implementación, no un certificado de terceros.' },
      ],
    },
    permissions: {
      title: 'Permisos y qué pasa sin ellos',
      body: 'Todos los permisos son opcionales, y cada uno es independiente de los demás.',
      items: [
        { term: 'Cámara y fotos seleccionadas', body: 'Necesarios para importar un informe desde una imagen. Los registros escritos a mano funcionan sin ellos.' },
        { term: 'Notificaciones', body: 'Necesarias para mostrar recordatorios de medicamentos. La agenda sigue en el teléfono cuando se deniegan.' },
        { term: 'Health Connect o Apple Salud', body: 'Acceso de solo lectura a frecuencia cardíaca, pasos y peso. Sin él, escribes esos valores.' },
        { term: 'Lo que nunca se pide', body: 'No hay cliente de red para datos de salud. En Android, el manifiesto final mantiene eliminado el permiso INTERNET.' },
      ],
    },
    deletion: {
      title: 'Cómo eliminar tus datos',
      body: 'Borrar es una operación normal en el dispositivo, y tiene límites que conviene conocer.',
      items: [
        { term: 'Dentro de la app', body: 'Borrar un registro lo quita de los registros activos. En Android, una generación anterior cifrada puede permanecer para recuperación; los archivos dañados también pueden conservarse en cuarentena. La eliminación de perfiles usa una ruta distinta. Borrar no garantiza la eliminación inmediata de todas las copias locales ni el borrado seguro.' },
        { term: 'Fuera de la app', body: 'Un archivo que ya exportaste, compartiste o guardaste como copia ya no está bajo control de la app. Bórralo donde lo dejaste.' },
        { term: 'Quitar la app', body: 'Desinstalar borra el almacenamiento privado de la app. Cualquier copia guardada en otro sitio se queda donde está.' },
        { term: 'Lo que no se puede borrar', body: 'No se guarda nada en un servidor, así que no hay nada que pedir que se borre. Esa es la ventaja práctica de no tener cuenta.' },
      ],
    },
    site: {
      title: 'Este sitio',
      body: 'Este sitio es una compilación estática. No pone cookies, no ejecuta analítica, no carga scripts de terceros y no envía nada sobre ti a un servicio de publicidad o rastreo. El sitio está alojado en Vercel, que procesa los datos de la petición como cualquier alojamiento web. Los únicos enlaces externos van al repositorio público y a la documentación.',
    },
    audit: {
      note: 'Todavía no se ha hecho una auditoría de seguridad independiente, y este sitio no reclama certificación regulatoria. Las declaraciones de implementación anteriores son las que el código sostiene.',
    },
    links: {
      policy: 'Leer la política de privacidad',
      details: 'Leer los detalles técnicos de privacidad',
      source: 'Leer el código',
    },
  },
};

/** Gallery entries reuse the three real captures; the crops differ per page so the gallery is not a repeat. */
export const galleryScreens: Record<Locale, Screenshot[]> = {
  en: [
    {
      src: '/assets/android-home',
      alt: 'Vezuna Today screen for the profile Ana Silva: a next-dose card for Vitamin D with Mark as taken, Skip and Snooze buttons, a 30-day adherence row, and the next reminders listed below.',
      caption: 'Today. The next dose, three ways to answer it, and recorded doses taken out of recorded completed doses over the last thirty days.',
    },
    {
      src: '/assets/android-labs',
      alt: 'Vezuna Records screen for Ana Silva: a banner reporting 3 values to review, a search field with filters, a line reading 6 confirmed reports saved on this device, and a list of reports from October 2026.',
      caption: 'Records. Drafts waiting for review sit above the confirmed reports, in the same list.',
    },
    {
      src: '/assets/android-settings',
      alt: 'Vezuna More screen with entries for Profiles, Exports, Backups, Privacy and app lock, Device connections, and Assistant and local models, above a bottom bar of Today, Records, Care, Trends and More.',
      caption: 'More. Profiles, exports, backups, and the controls that belong to the device rather than to a person.',
    },
  ],
  'pt-br': [
    {
      src: '/assets/android-home',
      alt: 'Tela Today do Vezuna para o perfil Ana Silva: cartão Next dose de Vitamin D com os botões Mark as taken, Skip e Snooze, uma linha de adesão de 30 dias e os próximos lembretes.',
      caption: 'Today. A próxima dose, três formas de responder, e doses registradas como tomadas em relação às doses com registro concluído nos últimos trinta dias.',
    },
    {
      src: '/assets/android-labs',
      alt: 'Tela Records do Vezuna para Ana Silva: aviso de 3 valores para revisar, busca e filtros, a linha 6 confirmed reports salvos neste aparelho e a lista de relatórios de outubro de 2026.',
      caption: 'Records. Os rascunhos à espera de revisão ficam acima dos relatórios confirmados, na mesma lista.',
    },
    {
      src: '/assets/android-settings',
      alt: 'Tela More do Vezuna com as entradas Profiles, Exports, Backups, Privacy and app lock, Device connections e Assistant and local models, acima da barra inferior Today, Records, Care, Trends e More.',
      caption: 'More. Perfis, exportações, backups e os controles que pertencem ao aparelho, não a uma pessoa.',
    },
  ],
  es: [
    {
      src: '/assets/android-home',
      alt: 'Pantalla Today de Vezuna para el perfil Ana Silva: tarjeta Next dose de Vitamin D con los botones Mark as taken, Skip y Snooze, una fila de adherencia de 30 días y los próximos recordatorios.',
      caption: 'Today. La próxima dosis, tres formas de responder, y dosis registradas como tomadas respecto a las dosis con registro completado en los últimos treinta días.',
    },
    {
      src: '/assets/android-labs',
      alt: 'Pantalla Records de Vezuna para Ana Silva: aviso de 3 valores por revisar, búsqueda y filtros, la línea 6 confirmed reports guardados en este dispositivo y la lista de informes de octubre de 2026.',
      caption: 'Records. Los borradores pendientes de revisión quedan encima de los informes confirmados, en la misma lista.',
    },
    {
      src: '/assets/android-settings',
      alt: 'Pantalla More de Vezuna con las entradas Profiles, Exports, Backups, Privacy and app lock, Device connections y Assistant and local models, sobre la barra inferior Today, Records, Care, Trends y More.',
      caption: 'More. Perfiles, exportaciones, copias de seguridad y los controles que pertenecen al dispositivo y no a una persona.',
    },
  ],
};
