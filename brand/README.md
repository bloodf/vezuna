# Brand kit Vezuna

Identidade visual e verbal para Vezuna, um projeto de hub pessoal de saúde. O kit reúne os elementos de marca e as instruções para aplicá-los ao produto existente, sem alterar funcionalidades, dados ou regras clínicas.

## Comece aqui

1. Leia `brand-guide.md` para entender a marca e suas regras de uso.
2. Abra `preview/brand-overview.pdf` ou `preview/brand-overview.png` para conferir a direção.
3. Entregue **a pasta completa**, e não apenas uma imagem, à IA que está desenvolvendo o aplicativo.
4. Cole o conteúdo de `IMPLEMENTATION_PROMPT.txt` na conversa com essa IA e indique o repositório ou projeto a modificar.
5. Revise as telas e os testes antes de integrar ou publicar as mudanças.

## Conteúdo

| Caminho | Uso |
|---|---|
| `brand-guide.md` | Guia editável da identidade, copy, acessibilidade, internacionalização e aplicação |
| `IMPLEMENTATION_PROMPT.txt` | Prompt completo para a IA responsável pelo código |
| `logos/` | Símbolo e assinatura horizontal em SVG, com variações para fundos claros, escuros e uma cor |
| `icons/` | Recursos de ícone para app e web; leia `icons/PLATFORM-NOTES.md` |
| `ui-icons/` | 12 ícones SVG de interface, com `currentColor` |
| `tokens/` | Valores de design em JSON, CSS e TypeScript |
| `fonts/` | Fontes locais e avisos de licença |
| `social/` | Peças base para compartilhamento, repositório e destaque |
| `preview/` | Visão geral da identidade em PDF, PNG e SVG |
| `qa/` | Relatórios de contraste e validação dos assets |
| `source/build_assets.py` | Fonte de geração dos assets |
| `SOURCES.md` | Referências e observações técnicas |

Os demais previews e documentos visuais do pacote ajudam na revisão; os SVGs são os masters da identidade. Os nomes de arquivos são técnicos; a marca visível é sempre **Vezuna**.

## Escolha da versão do logo

- Fundo claro: `logos/lockup-color.svg` ou `logos/mark-color.svg`.
- Fundo escuro: `logos/lockup-dark.svg` ou `logos/mark-dark.svg`.
- Uma cor: variantes `ink` para fundos claros e `white` para fundos escuros.
- Espaço pequeno: símbolo isolado; nunca a assinatura completa espremida.

Não redesenhe o símbolo nem altere suas proporções. O guia explica respiro e tamanhos mínimos. Use o SVG para o produto e os PNGs no tamanho adequado para canais que exigirem raster.

## Uso dos tokens

Parta de `tokens/tokens.json` e do mapeamento semântico existente no projeto. As versões CSS e TypeScript facilitam a integração, mas não exigem uma stack específica. Não mantenha valores divergentes entre cópias nem suponha que as cores, sozinhas, garantem acessibilidade.

## Antes de publicar

- Validar claro e escuro, mobile, texto ampliado, teclado e RTL.
- Confirmar que a copy descreve recursos realmente disponíveis.
- Usar dados fictícios nas capturas de tela.
- Conferir os requisitos atuais das plataformas de destino e testar os ícones no build real.
- Revisar a licença do código, os avisos das fontes e as condições de uso do nome separadamente.

Os relatórios do kit verificam assets e pares de cores; não houve compilação de um aplicativo ou teste de integração nativa. As peças promocionais não são screenshots de funcionalidades.

O kit não é uma liberação jurídica do nome, uma certificação de acessibilidade ou uma auditoria de segurança. A identidade não declara que integrações de saúde estejam funcionando nem substitui a validação do produto.

## Licença

A arte original da Vezuna neste diretório (logos, símbolos, ícones, imagens sociais, tokens, previews e texto do guia) está sob licença MIT, conforme `LICENSE` neste diretório, por decisão do proprietário do projeto. O proprietário informou a compra de `vezuna.app` em 31 de outubro de 2026. A compra do domínio não estabelece registro de marca nem reserva nas lojas. A licença não concede direitos de marca, e ninguém pode usar o nome ou o logotipo para sugerir endosso ou para identificar outro produto como o aplicativo oficial. As fontes em `fonts/` (Noto Sans) não são relicenciadas e continuam sob a SIL Open Font License 1.1, conforme `fonts/NOTO-LICENSE.txt`.

