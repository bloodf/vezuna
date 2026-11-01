---
title: Primeiros passos
description: Crie um perfil fictício, revise resultados importados e entenda permissões, backups e suporte.
lang: pt-br
order: 1
audience: user
translationKey: getting-started
essential: true
---

O Vezuna organiza registros de saúde da família no Android e iPhone, sem exigir uma conta no app. O mínimo atual no código-fonte é **Android 8.0 (API 26)**. No iPhone, é necessário **iOS 18.5 ou posterior**. Os testes de lançamento em dispositivos físicos continuam pendentes.

## Exemplo sintético de primeiro uso

1. Crie um perfil fictício chamado **Alex Exemplo**.
2. Importe uma imagem ou PDF laboratorial fictício com **Hemoglobina — 13,2 g/dL**. Copie a data e o intervalo de referência do próprio documento sintético.
3. Confira o rascunho extraído, corrija nome, valor, unidade, referência ou data e confirme o resultado.
4. Importe um segundo relatório fictício com **Hemoglobina — 13,8 g/dL**, revise e confirme.
5. Abra a comparação para visualizar os resultados confirmados ao longo do tempo.
6. Para testar lembretes, cadastre **Medicamento Demonstrativo**, escolha um horário e use as ações **tomado**, **pulado** ou **adiado**. Esse exemplo não é uma recomendação de dose.

O OCR produz rascunhos que precisam ser conferidos. Somente resultados confirmados aparecem nas comparações e exportações. O Vezuna não diagnostica doenças, não recomenda tratamentos e não altera doses.

## Permissões opcionais

Com sua permissão, o Vezuna lê frequência cardíaca, passos e peso do Health Connect no Android ou HealthKit no iPhone. O acesso é somente para leitura. Recusar essa permissão não impede o uso dos registros manuais.

Câmera e imagens selecionadas apoiam a importação de relatórios. Notificações apoiam os lembretes locais de medicamentos.

## Privacidade, exportações e exclusão

Não envie exames, receitas, capturas de registros, arquivos do cofre ou backups reais para issues, pull requests, relatórios de segurança ou serviços externos de IA e análise. Use somente dados fictícios.

PDFs e CSVs compartilhados são texto simples e ficam fora da proteção do cofre. Ao remover o app, o cofre local e os modelos baixados são apagados; arquivos já exportados não são removidos.

Os serviços das lojas podem cuidar do licenciamento e da entrega planejada de modelos. O comportamento de privacidade de SDKs e plataformas precisa de verificação separada no build de lançamento.

## Backups e identidade do app

Os backups novos são arquivos portáteis protegidos por uma frase-senha que você escolhe (pelo menos 12 caracteres). Dá para restaurar em outro celular ou depois de reinstalar o app. O Vezuna não recupera uma frase-senha perdida: sem ela, o backup nunca mais abre. Backups feitos por builds anteriores só abrem no mesmo aparelho, com a mesma chave, e o app os identifica assim.

O identificador atual é `app.vezuna`. Mudar esse identificador cria uma instalação separada e não transfere a sandbox anterior nem as chaves do Android Keystore.

## Modelos offline

Existe código para inferência local, mas o catálogo de produção está vazio. Não há modelos públicos para baixar neste site. Texto gerado por IA pode estar errado e não é orientação médica. Registros, backups e exportações não dependem de modelo ou licenciamento de IA.

## Suporte

Para dúvidas sobre privacidade ou problemas comuns, use as [issues do GitHub](https://github.com/bloodf/vezuna/issues). Para vulnerabilidades, abra **Security → Report a vulnerability** no repositório. Não publique detalhes de exploração nem dados de saúde.

Inclua a plataforma, a versão do app ou commit, os passos de reprodução e o impacto, sempre com dados sintéticos. O projeto é mantido com melhor esforço, sem compromisso de tempo de resposta ou programa de recompensas.
