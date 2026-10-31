# Guia de marca Vezuna

Versão 1.0 · 31 de outubro de 2026

Este guia define a identidade visual e verbal de Vezuna para o aplicativo, o repositório e a comunicação do projeto. A direção é simples: tornar informações pessoais de saúde mais fáceis de encontrar e organizar, sem criar uma aparência de diagnóstico ou prometer capacidades que ainda não existem.

## A marca

**Nome:** Vezuna. Escrever sempre com V maiúsculo e o restante em minúsculas. Não traduzir, acentuar, separar nem acrescentar “Health”, “AI” ou “Medical” ao logotipo.

**Categoria proposta:** hub pessoal de saúde de código aberto.

**Posicionamento:** um lugar para organizar medicamentos, resultados de exames e dados de saúde conectados, respeitando o que a implementação realmente oferece.

**Personalidade:** clara, acolhedora e discreta. A identidade deve ajudar a pessoa a se orientar, não chamar mais atenção que suas informações.

**Ideia do símbolo:** duas curvas arredondadas formam um V aberto em torno de um ponto. O ponto representa a pessoa; as curvas, informações reunidas ao seu redor. É uma metáfora visual, não um símbolo de proteção técnica comprovada.

Não usar cruz médica, eletrocardiograma, escudo, cadeado ou selo de aprovação como substitutos do símbolo. Não associar a marca a uma nacionalidade, religião ou instituição de saúde.

## Assinatura e mensagens

### Assinatura principal

| Idioma | Texto |
|---|---|
| Inglês internacional | Your health, in one place. |
| Português | Sua saúde, em um só lugar. |
| Espanhol | Tu salud, en un solo lugar. |

A assinatura expressa o propósito da marca; não significa que todo tipo de dado, serviço ou dispositivo já está integrado. Em materiais de produto, a descrição deve esclarecer o escopo disponível.

### Copy internacional

**Descrição curta:** A personal health hub.

**Apresentação do projeto:** Vezuna is an open-source project for a personal health hub, designed to bring medications, lab results, and connected health data into one place.

**Hero:** Your health, in one place.

**Texto de apoio:** Bring your health information together, with a clearer view of what matters to you.

**Chamadas para ação:** Explore Vezuna · View the project · Read the documentation.

**Descrição para repositório:** An open-source personal health hub for medications, lab results, and connected health data.

Esses textos descrevem a direção do projeto. Antes da publicação, confirmar a licença do repositório e quais funcionalidades estão disponíveis. Em páginas de uma versão lançada, citar apenas recursos implementados e testados. “Open-source” não deve substituir um arquivo de licença válido.

### Copy em português

**Descrição curta:** Um hub pessoal de saúde.

**Apresentação do projeto:** Vezuna é um projeto de código aberto para um hub pessoal de saúde, pensado para reunir medicamentos, resultados de exames e dados de saúde conectados em um só lugar.

**Texto de apoio:** Reúna suas informações de saúde e encontre com mais clareza o que importa para você.

**Chamadas para ação:** Conheça o Vezuna · Veja o projeto · Leia a documentação.

## Voz e tom

Escrever frases curtas, com verbos concretos. Explicar o que ocorreu, o que a pessoa pode fazer e o que será alterado. Evitar linguagem clínica quando uma palavra comum basta. Não infantilizar, pressionar nem culpar.

| Situação | Preferir | Evitar |
|---|---|---|
| Tela vazia | Nenhum exame adicionado ainda. | Sua saúde está incompleta. |
| Ação | Adicionar medicamento | Otimize seu tratamento agora |
| Confirmação | Registro salvo. | Você está mais saudável! |
| Erro | Não foi possível salvar. Tente novamente. | Algo deu errado!!! |
| Sem conexão | Sem conexão. Algumas informações podem estar desatualizadas. | Tudo está sincronizado. |
| Pedido de acesso | Escolha quais dados deseja conectar. | Conecte tudo para ficar protegido. |
| Resultado importado | Resultado recebido do laboratório. | Diagnóstico confirmado. |

Usar “Salvo neste dispositivo”, “Sincronizado” ou “Seus dados não saem do aparelho” somente quando o comportamento tiver sido verificado. Não anunciar criptografia, conformidade regulatória, precisão clínica ou disponibilidade universal sem evidência específica.

## Logotipo

### Construção

O master do símbolo usa um quadro de 100 × 100 unidades. São duas curvas com espessura de 14 unidades, pontas e junções arredondadas, e um ponto central de raio 7. Usar os SVGs fornecidos em vez de redesenhar a geometria. A assinatura horizontal reúne o símbolo e o nome com letras convertidas em curvas; não depende de uma fonte instalada para renderizar corretamente.

### Versões

| Arquivo | Aplicação |
|---|---|
| `logos/mark-color.svg` | Símbolo principal em fundo claro |
| `logos/lockup-color.svg` | Assinatura principal em fundo claro |
| `logos/mark-dark.svg` e `logos/lockup-dark.svg` | Versões reversas para fundo escuro |
| `logos/mark-white.svg` e `logos/lockup-white.svg` | Uma cor branca em fundo escuro |
| `logos/mark-ink.svg` e `logos/lockup-ink.svg` | Uma cor escura em fundo claro |

“Dark” indica o contexto de uso em tema escuro, não um fundo embutido. Confirmar a transparência do arquivo antes de exportar.

### Respiro e tamanho

Definir **x** como o diâmetro do ponto central. Manter no mínimo **1x de área livre** ao redor dos limites visíveis do símbolo ou da assinatura, sem textos, bordas ou outros elementos. O espaço transparente no SVG pode contribuir para esse respiro; medir o desenho, não apenas o retângulo do arquivo.

- Símbolo digital: mínimo recomendado de **24 px** de largura; preferir 32 px ou mais em cabeçalhos.
- Favicon: usar a exportação de **16 px** fornecida e conferir a legibilidade no navegador; não reduzir uma assinatura completa.
- Assinatura horizontal: mínimo recomendado de **120 px** de largura; aumentar se a leitura ficar comprometida.
- Impressão: referência inicial de 8 mm para o símbolo e 30 mm para a assinatura; revisar uma prova no tamanho real.
- Em avatares e ícones, usar apenas o símbolo. Não incluir a assinatura verbal dentro do ícone.

Não esticar, inclinar, espelhar, rotacionar, redesenhar, adicionar contorno, trocar cores isoladamente, aplicar sombra ou transformar o símbolo em um padrão dentro da assinatura. Não pôr a versão clara sobre uma fotografia movimentada. Preferir uma superfície lisa com contraste suficiente.

## Cores

### Paleta da marca

| Nome | HEX | Função |
|---|---|---|
| Navy | `#123B46` | Identidade principal e títulos em contexto de marca |
| Teal | `#087F8C` | Ação principal e acento no tema claro |
| Aqua | `#71E2CC` | Acento no tema escuro e áreas decorativas |
| Offwhite | `#F5FAF9` | Fundo claro |
| Ink | `#152E35` | Texto principal em fundo claro |
| Deep | `#0C2027` | Fundo escuro |
| Mist | `#E7F5F2` | Texto principal em fundo escuro |

O navy dá estrutura. O teal identifica ações. O aqua traz leveza sem ocupar grandes áreas de leitura. Fundos neutros e bastante espaço vazio devem predominar. Não usar aqua como texto pequeno sobre branco ou offwhite. Não usar branco sobre aqua em botões; usar texto escuro.

### Tokens e significado

`tokens/tokens.json` é a referência de valores do kit; `tokens/tokens.css` e `tokens/tokens.ts` são formatos para integração. Adaptar os nomes à arquitetura existente sem duplicar fontes de verdade.

Separar **cores primitivas** das funções de interface: fundo, superfície, texto, texto secundário, borda, ação, ação em hover, foco, sucesso, aviso, erro e informação. Os temas claro e escuro precisam de seus próprios mapeamentos semânticos; não basta inverter todas as cores.

Vermelho comunica erro ou risco quando houver motivo real. Amarelo comunica atenção. Verde pode confirmar uma ação concluída; nunca afirmar que a pessoa está “saudável” apenas por cor. O teal da marca não é sinônimo de resultado clínico normal. Sempre combinar cor com texto e, quando útil, ícone ou padrão.

Gráficos devem manter o significado de cada série nos dois temas, usar rótulos, linhas ou símbolos distinguíveis e oferecer uma alternativa textual. Preservar a origem, a data, a unidade e os intervalos de referência dos dados existentes. A mudança de marca não autoriza interpretar resultados nem converter unidades.

## Tipografia

**Fonte de interface:** Noto Sans. Usar Regular 400 para leitura e Bold 700 para ênfase. Não simular um peso que não foi incluído. As fontes locais e sua licença acompanham o kit.

**Fallback:** `"Noto Sans", system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif`.

Para outros sistemas de escrita, carregar a família Noto apropriada ou um fallback local que cubra o idioma. A presença de Noto Sans no kit não garante cobertura de todos os alfabetos. Não buscar fontes em um CDN sem uma decisão explícita de produto e privacidade.

| Uso | Tamanho de referência | Entrelinha | Peso |
|---|---|---|---|
| Hero de apresentação | 40 px | 1,2 | 700 |
| Título de página ou seção | 24 px | 1,2 | 700 |
| Corpo e controles | 16 px | 1,5 | 400 ou 700 |
| Informação secundária | 14 px | 1,5 | 400 |
| Legenda não essencial | 12 px | 1,5 | 400 |

Esses valores são pontos de partida, não alturas fixas. Respeitar ampliação de texto e configurações do sistema. Não usar 12 px para doses, instruções, avisos ou resultados. Preferir números tabulares em tabelas e séries quando a fonte e a plataforma oferecerem suporte. Evitar longos trechos em maiúsculas.

## Interface

### Ritmo e forma

- Unidade de espaçamento: 4 px. Escala do kit: 4, 8, 16, 24, 32 e 48.
- Raios: 12 px em controles, 20 px em cartões e 999 px para pills. Usar pills e círculos apenas onde o conteúdo justificar.
- Um botão principal por região de decisão. Ações secundárias devem ser visualmente mais leves.
- Botões e campos devem manter rótulos explícitos. Placeholder não substitui label.
- Áreas de toque preferenciais: 44 pt em iOS, 48 dp em Android e 48 CSS px na web, com distância suficiente entre ações.
- Preferir separação por espaço e bordas sutis a várias camadas de sombra.
- Animações de 120 ms para transições rápidas e 200 ms para transições comuns, sem pulsação clínica ou alertas decorativos. O token de movimento reduzido é 0 ms.

### Componentes

**Botão principal:** teal com texto branco no tema claro; aqua com texto escuro no tema escuro. Validar os estados normal, hover, pressionado, foco, carregando e desabilitado. Desabilitar visualmente não pode esconder a explicação de uma restrição.

**Cartão de medicamento:** nome, dose registrada, unidade e horário com hierarquia clara. Preservar as regras do produto sobre registrar uma tomada. O redesign não deve criar confirmações automáticas nem sugestões de dose.

**Cartão de exame:** título, data, origem e status de importação; abrir os detalhes existentes. Não transformar ausência de alerta em garantia de normalidade.

**Conexões:** mostrar quais fontes estão conectadas, o estado real da sincronização e permissões disponíveis. Não prometer integração com iOS, Android ou qualquer provedor antes da verificação técnica.

**Ícones de interface:** o diretório `ui-icons/` inclui 12 SVGs com quadro de 24 unidades, traço de 1,8 e `currentColor`: medicamento, exame, atividade, registros, calendário, privacidade, conexão, check, fechar, seta, adicionar e configurações. Usar esse conjunto quando fizer sentido ou preservar uma biblioteca existente consistente; não adicionar dependências só para o rebrand. Símbolos ambíguos precisam de rótulos. O ícone de privacidade representa uma área da interface, não uma garantia de segurança.

**Imagens:** preferir abstrações suaves, recortes de interface com dados fictícios e fotografia humana não estereotipada. Não usar exames reais, prescrições, nomes ou telas pessoais em peças promocionais.

## Acessibilidade

**Meta de implementação: WCAG 2.2 nível AA**, sem alegação de certificação. Validar o produto completo, não somente a paleta. Texto normal deve atingir contraste de 4,5:1; texto grande, 3:1. Elementos visuais necessários para identificar controles ou estados devem atingir 3:1 em relação às cores adjacentes, observadas as exceções do padrão. Veja os critérios oficiais de [contraste de texto](https://www.w3.org/TR/WCAG22/#contrast-minimum) e [contraste não textual](https://www.w3.org/TR/WCAG22/#non-text-contrast).

Testar foco visível e não encoberto, navegação por teclado, ordem de leitura, nomes acessíveis, erros associados aos campos, leitores de tela e ampliação. Não depender apenas de cor. O padrão AA estabelece condições para alvos de ponteiro com mínimo de 24 × 24 CSS px e exceções; o kit adota 44–48 px como preferência de usabilidade para toque. Veja [tamanho mínimo de alvo](https://www.w3.org/TR/WCAG22/#target-size-minimum).

Usar `alt="Vezuna"` quando o logotipo comunica a marca sozinho. Quando o nome já estiver no mesmo link ou o desenho for decorativo, evitar leitura duplicada com texto alternativo vazio ou o equivalente nativo.

## Internacionalização

Vezuna é o mesmo nome em todos os idiomas. A interface deve falar a língua escolhida pela pessoa; a copy em inglês é uma base internacional, não uma ordem para substituir os idiomas existentes.

- Externalizar textos e usar identificadores estáveis de tradução. Não concatenar partes de frases.
- Não usar bandeiras para representar idiomas. Exibir o nome do idioma e distinguir região quando necessário.
- Formatar datas, horários e números conforme localidade. Evitar datas numéricas ambíguas e preservar unidades originais.
- Reservar espaço para textos maiores. Quebrar linha naturalmente; não truncar instruções importantes.
- Usar propriedades direcionais lógicas e testar escrita da direita para a esquerda. Espelhar controles direcionais quando apropriado, nunca o logotipo, números ou gráficos indiscriminadamente.
- Não pressupor sobrenome, formato de endereço, sistema de saúde ou unidade de medida únicos.
- Revisar traduções de conteúdo médico com pessoas qualificadas antes de publicá-las.

## Conteúdo de saúde e confiança

O aplicativo deve distinguir registros pessoais, informações importadas e conteúdo gerado. A identidade visual não transforma registros em orientações clínicas.

Para áreas que mostrem resultados ou informações potencialmente interpretáveis, uma base de microcopy é: **“As informações aqui ajudam a organizar seus registros e não substituem a avaliação de um profissional de saúde.”** Em inglês: **“This information helps organize your records and does not replace advice from a qualified healthcare professional.”** Adaptar a apresentação ao contexto; um aviso não corrige uma funcionalidade insegura.

Não usar “diagnóstico inteligente”, “previne doenças”, “tratamento ideal”, “100% seguro” ou “clinicamente aprovado” sem fundamento validado e revisão apropriada. Não apresentar uma animação, um selo ou o símbolo da marca como prova de privacidade ou segurança.

## Aplicações e arquivos

- **Produto:** assinatura no onboarding e espaços de marca; símbolo em áreas compactas; marca discreta nas telas de trabalho.
- **iOS:** usar a coleção em `icons/ios/AppIcon.appiconset`. Conferir no projeto real as variantes, requisitos e validação do asset catalog.
- **Android:** usar os recursos em `icons/android/res`, preservando camadas e zona segura. Testar máscaras, recortes e ícones temáticos no build real.
- **Web:** usar SVG para interface, favicon próprio e os tamanhos de ícone fornecidos. Não alterar manifesto ou identificadores do aplicativo sem verificar o projeto.
- **Compartilhamento:** `social/og-1200x630.png` para Open Graph, `social/github-1280x640.png` para apresentação do repositório e `social/feature-1024x500.png` como base de destaque. Esses formatos são entregas de design; validar requisitos atuais de cada destino antes do envio.

Consultar `icons/PLATFORM-NOTES.md` antes da integração nativa. Os assets promocionais são peças de marca, não capturas de funcionalidades.

Manter arquivos vetoriais como masters. Exportar PNGs a partir deles, sem ampliar um raster pequeno. Não modificar códigos de cor entre exportações. A política de redução do ícone é diferente da área de respiro de uma assinatura em uma página.

## Verificação do kit

`preview/brand-overview.pdf` e `preview/brand-overview.png` mostram uma visão geral da identidade. `qa/contrast.json` registra as combinações de cores medidas e `qa/assets-validation.json` registra verificações dos arquivos. Esses relatórios não representam teste de acessibilidade de um aplicativo completo, compilação nativa ou aprovação de lojas. Os links de referência estão em `SOURCES.md`.

## Licenças e lançamento

Este kit não declara disponibilidade de marca, domínio, nome de usuário ou registro. Não houve liberação jurídica de “Vezuna”. Antes de um lançamento público, pesquisar conflitos relevantes nos mercados pretendidos e definir uma política de marca.

A licença do **código-fonte**, a permissão de uso da **marca e dos elementos visuais** e a licença das **fontes** são assuntos separados. Não inventar MIT, Apache, GPL ou outra licença para o projeto. Preservar a licença que o titular escolher para o código e os avisos reais das fontes incluídas. O kit não concede automaticamente a terceiros o direito de se apresentarem como Vezuna.

### Checklist de aplicação

- [ ] Nome escrito corretamente e logotipo original preservado.
- [ ] Respiro e leitura conferidos em tamanho real.
- [ ] Tokens centralizados e temas claro e escuro completos.
- [ ] Textos, estados e ícones acessíveis, sem significado só por cor.
- [ ] Mobile, texto ampliado, teclado, leitor de tela e RTL testados.
- [ ] Copy pública compatível com as funcionalidades demonstráveis.
- [ ] Nenhuma promessa clínica, de privacidade ou segurança sem evidência.
- [ ] Dados fictícios em screenshots, exemplos e testes.
- [ ] Licenças, marca e requisitos dos canais revisados antes de publicar.

