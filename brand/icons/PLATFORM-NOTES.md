# Integração dos ícones

## iOS
Copie AppIcon.appiconset para um asset catalog existente sem substituir outras imagens. O conjunto traz PNGs 1024×1024 opacos: default, dark e tinted, com Contents.json de aparência universal iOS. Confira compatibilidade com o Xcode/deployment target do app. Não há arquivo .icon do Icon Composer: os SVGs são fontes editáveis, não um projeto Liquid Glass certificado. Não desenhe cantos arredondados no arquivo; o sistema aplica a máscara. Compile e confira em dispositivo antes de publicar.

## Android
Mescle res/ cuidadosamente. Há ícones legados por densidade, adaptive-icon v26 e monochrome v33, foreground/background em XML e fontes SVG/PNG. Camadas 108dp; arte central contida na zona segura 66dp. Configure android:icon para @mipmap/ic_launcher conforme o projeto. Não configure roundIcon para um recurso inexistente. play-store-512.png é a imagem da ficha, não o adaptive icon. Teste máscaras e tema monocromático em Android real; XML foi verificado sintaticamente, não compilado em app.

## Web
PNG 16/32/48/180/192/512 e favicon.ico. O fragmento de manifest deve ser mesclado; ajuste caminhos relativos. purpose:any, não declarar maskable sem testar o recorte. Ícone 180 serve de candidato apple-touch-icon. Valide ícones pequenos na interface real.

## Assets promocionais
social/ contém imagens de marca e fundos com slogan, não screenshots de funcionalidades. Não apresentar como telas reais nem declarar aprovação de loja. Substitua fundos por capturas reais somente quando existirem.

## Fontes
Noto Sans Regular/Bold e aviso de licença estão em fonts/. Latin/Greek/Cyrillic desta distribuição não significam cobertura de todos os idiomas. Configure Noto da escrita correspondente ou fallback do sistema. Fontes carregadas localmente evitam requisição a terceiros. Preserve a licença SIL OFL presente em NOTO-LICENSE.txt.
