# Porta para a 1.7.10

Base: Yes Steve Model `dev/1.20`, commit `15c2c25` (2026-11-01). O estudo completo está no Kunftig,
`docs/playermodel/ESTUDO-YSM.md`.

## Decisões

| Tema | Decisão |
|---|---|
| Java | Cliente Java 8, servidor Java 21, um jar só: tudo em bytecode Java 8 (Jabel) |
| Formato | Só o aberto (`ysm.json` spec 2 e o layout 1.x). Nada de `.ysm` cifrado nem biblioteca nativa |
| Molang | Interpretador próprio em Java 8. O do upstream depende do Semaweave (jars binários Java 17, sem fonte nem licença no jar) e do Janino |
| Desenho | Próprio, em Java: geometria de cada osso em display list, pose aplicada por quadro. Sem o GeckoLib 1.0.4 e sem a parte nativa |
| Rede | Canal próprio de plugin message: catálogo, arquivos em fragmentos < 32 KB, cache por hash. Nunca desserialização Java nem Molang vindo do servidor |
| Gson | O do jogo (2.2.4): os adaptadores que o upstream liga com `@JsonAdapter` são registrados em `format/FormatGson`; nada de Gson embutido |
| Pacotes | `com.akashic.futura.*`; cada arquivo adaptado diz no cabeçalho de onde veio |

## Mapa do upstream

| Upstream (`com.elfmcys.ysm`) | Linhas | Na 1.7.10 | Estado |
|---|---|---|---|
| `format/parser/pojo` (POJOs de ysm.json, geometria, animação, controladores) | 1,4 mil | `format/pojo` | **Portado**; teste lê os 24 modelos embutidos |
| `format/parser` (montagem) | 2,6 mil | O upstream monta um container protobuf com Molang compilado e buffers nativos; aqui monta direto o modelo em memória | A fazer |
| resto de `format` (schema, container, legacy, media) | 5 mil | Container `.mxc`, importador legado e áudio Opus: não vêm | Fora |
| `model` (catálogo, carregamento) | 25,2 mil | Vem com ajustes (15 de 171 usam Minecraft) | A fazer |
| `geckolib3` (controladores, processador, ossos) | 6,3 mil | Lógica vem; desenho reescrito | A fazer |
| `expression` (Molang) | 10,7 mil | Gramática e funções como especificação; motor novo | A fazer |
| `client` (render, GUI, controladores do jogador) | 21,4 mil | Lógica dos controladores vem; render e GUI reescritos | A fazer |
| `network`, `capability`, `mixin`, `event`, `command`, `config` | ~14 mil | Reescritos para a 1.7.10 | A fazer |
| `natives` | 4,1 mil | Não vem | Fora |

## Fases

1. **Prova:** ler um modelo CC0 embutido, assar em display lists, desenhar no lugar do jogador local; medir ms/quadro
   com 1, 10 e 30 cópias, com e sem Angelica.
2. **Animação:** clips, keyframes, pilha de controladores, mistura (os `parallel*` somam rotação), estados `main`,
   Molang, `ysm.second_order`/`first_order`, `ysmGlow`.
3. **Jogador completo:** itens nos locators, primeira pessoa (`arm.json`, `Background`), escala, emotes `extra`.
4. **Servidor:** catálogo, fragmentos, cache, seleção, autorização, roleta e seletor.
5. **Extras:** controladores Bedrock, funções `@evento`, sons Vorbis, `v.roaming`, SlashBlade e Touhou Little Maid.

O critério de "emulado" é paridade lado a lado com o YSM 1.20 nos mesmos modelos abertos: mesma pose em cada estado,
mesma física, mesmo piscar, emotes, brilho e sons. Animações de mods que não existem na 1.7.10 (TaCZ, Carry On,
ParCool, SWEM) nunca disparam.
