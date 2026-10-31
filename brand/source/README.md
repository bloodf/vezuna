# Fontes de construção

SVGs são os masters editáveis. Wordmarks foram convertidos a paths a partir de Noto Sans Bold; não dependem de fonte para exibição.

build_assets.py reproduz os assets visuais principais com Python 3, fontTools, Pillow e Inkscape no PATH. Use em cópia de trabalho: ele regrava arquivos de saída. Não instala dependências. O script não reconstrói a documentação editorial, todos os complementos CSS ou o PDF; esses arquivos devem ser preservados. Para ajustes de implementação, prefira tokens/tokens.json como fonte de valores.

O pacote não contém projeto do app nem altera automaticamente seu repositório. Não executar scripts desconhecidos sem leitura prévia.
