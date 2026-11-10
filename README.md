# 3D Futura Engine

Modelos de jogador customizados (Bedrock) para o **Minecraft 1.7.10** (Forge), para o modpack TEOF.

É uma bifurcação do [Yes Steve Model](https://github.com/YesSteveModel/YesSteveModel) (Apache-2.0), sem afiliação
com a equipe do YSM. O objetivo é rodar na 1.7.10 modelos do nível dos atuais (100+ ossos, ~1000 cubos, texturas
512², física de cabelo e saia por Molang) e fazer os nossos.

* Lê o formato **aberto** de autoria: uma pasta ou zip com `ysm.json` (`"spec": 2`), geometria e animações Bedrock,
  Molang, texturas PNG. Os modelos que funcionam no YSM moderno nesse formato devem funcionar aqui.
* Suporta modelos no formato aberto de autoria e compatibilidade com binários legados (.ysm) via biblioteca nativa oficial (YesSteveModel-Native, Apache-2.0) carregada estritamente em memória.
* Os modelos embutidos (`src/main/resources/assets/futura3d/builtin`) mantêm a licença e os autores escritos em cada
  `ysm.json` (CC0 ou CC BY-NC-SA 4.0: nada de venda).

Estado: **início da porta**. Ver [docs/PORTING.md](docs/PORTING.md) para o plano e o que já veio do upstream.

## Branches

| Branch | Conteúdo |
|---|---|
| `main` | A porta 1.7.10 (este projeto) |
| `dev/1.20` | O upstream 1.20 intacto, de onde os pacotes são trazidos |

## Build

Gradle (convenção GTNH / RetroFuturaGradle). O cliente roda Java 8, então o código do mod é **bytecode Java 8**
(Jabel: sintaxe moderna sim, `record` e APIs do Java 9+ não). O servidor roda Java 21, mas é o mesmo jar.

```bash
./gradlew spotlessApply build
```

## Licença

Código: Apache-2.0 (`LICENSE`), com os avisos em `NOTICE.md`. Modelos embutidos: a licença de cada `ysm.json`.
