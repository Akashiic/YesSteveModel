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
| `geckolib3` (controladores, processador, ossos) | 6,3 mil | Lógica vem; desenho reescrito | **Lógica portada** em `anim` (controladores Bedrock: fase 5) |
| `expression` (Molang) | 10,7 mil | Gramática (`frontend/MolangSourceParser`) e `math.*` (`MathBuiltins`, `ControlBuiltins`) adaptados em `molang/`; motor novo (interpretador de árvore) | **Interpretador feito**; funções do hospedeiro (`query`, `ysm`, `ctrl`...) a fazer na animação |
| `client` (render, GUI, controladores do jogador) | 21,4 mil | Lógica dos controladores vem; render e GUI reescritos | A fazer |
| `network`, `capability`, `mixin`, `event`, `command`, `config` | ~14 mil | Reescritos para a 1.7.10 | A fazer |
| `natives` | 4,1 mil | Não vem | Fora |

## Fase 1 (prova): resultado

Feito: `geo/GeoBaker` monta a geometria (portado do `GeoBuilder` do upstream, testado), `model/ModelLoader` lê um modelo
inteiro de uma `ModelSource`, `client/render/GeometryRenderer` compila cada osso numa display list e aplica só as
transformações por quadro, e `mixin/mixins/RenderPlayerMixin` troca a chamada interna `super.doRender` do
`RenderPlayer` (os eventos `RenderPlayerEvent.Pre`/`Post` continuam disparando; sombra, fogo e nome continuam).

![Modelo default de frente](img/fase1-default-frente.png)
![Modelo default de lado](img/fase1-default-lado.png)
![30 cópias](img/fase1-default-30-copias.png)

Harness (`dev/RenderHarness`, só com `./gradlew runClient -Pharness` e um arquivo `run/client/futura3d-harness`), em
**Mesa llvmpipe (renderização por software, na CPU)**, 854x480:

| Modelo | Cubos (quads) | Quadro sem cópias | +1 cópia | +10 cópias | +30 cópias |
|---|---|---|---|---|---|
| `default` | 172 (1032) | 23,4 ms | 30,2 ms | 37,4 ms | 39,9 ms |
| `wine_fox/18_wedding` | 1554 (6817) | 36,7 ms | 38,3 ms | 46,7 ms | 73,3 ms |
| `wine_fox/08_sta` | 4533 (26564) | 48,9 ms | 54,2 ms | 96,9 ms | 186,0 ms |

* Os números são de rasterização por software: servem para comparar entre si, não para prever uma placa de vídeo.
  O quadro "sem cópias" já inclui o próprio jogador com o modelo perto da câmera (preenchimento de tela).
  **A checar** numa máquina real e com Angelica.
* Na pose de repouso aparecem os adereços que as animações escondem (a espada gigante da Wedding, o tanque da Sta):
  eles têm escala 0 numa `pre_parallel`. O renderizador já pula ossos com escala 0, então com a fase 2 o custo real
  desses modelos cai.

## Molang

`molang/Molang.parse` compila uma vez (cache por texto, seguro entre threads) para uma árvore de nós; `evaluate` e
`evalNumber` rodam contra um `MolangContext` (funções do hospedeiro e `v.*`). Segue o `MolangSourceParser` do upstream:
mesmos tokens, precedências, apelidos (`q`→`query`, `c`→`context`, `v`/`variable`, `t`/`temp`, `r`/`roaming`),
`c ? a` sem `:`, `??`, `->`, blocos, `loop`, `for_each`, `break`/`continue`/`return`. Fail-soft: o que não compila
vira a constante 0; avaliar nunca lança. Testes: `src/test/java/com/akashic/futura/molang/`, incluindo um que compila
todos os textos Molang das animações e controladores embutidos.

Semântica do upstream que o interpretador reproduz (o motor dele, Semaweave, é binário; parte disto vem do bytecode e
das políticas em `ExpressionPolicy`, por isso **a checar** lado a lado com o YSM 1.20):

* números em float de 32 bits (`0.1 + 0.2 == 0.3` é verdadeiro); seno e cosseno em graus;
* valor ausente (variável nunca escrita, função desconhecida, número não finito do hospedeiro, `c ? a` falso) vale
  0 / `''`, mas `??` o distingue de 0, `false` e `''`;
* falha de execução zera o programa inteiro (as escritas anteriores ficam): resultado final não finito (`1/0`,
  `0/0`), argumento não finito passado a uma função, `math.sqrt(-1)`, `math.random_integer(a, a)`, mais de 10 000
  iterações de laço ou chamadas por avaliação. Exemplo nos modelos embutidos: `q.vertical_speed/math.abs(q.vertical_speed)`
  parado zera o canal inteiro (o YSM antigo dividia por 1);
* `math.random_integer` tem limite superior exclusivo; `math.clamp(v, lo, hi)` é `min(max(v, lo), hi)`;
* string não vazia é verdadeira e vale 1 (`'0'` também); `==` compara strings pelo conteúdo.

Fora: arrays (só existem como retorno de extensões Java no upstream; `for_each` e `a[i]` compilam e não fazem nada),
funções de usuário e `args`/`this` próprios (vão para o hospedeiro como namespace `fn`, `args`, `global`), a tipagem
estática do upstream (um programa com tipos incompatíveis lá é trocado por 0; aqui roda com as conversões numéricas).

## Fase 2 (animação): resultado no jogo

O cliente anima os jogadores: `client/PlayerInputsAdapter` preenche um `PlayerSnapshot` a partir do jogador da 1.7.10
a cada quadro, `client/PlayerModels` mantém um `PlayerAnimator` por jogador, e a pose vai para o `GeometryRenderer`.
O harness fotografa parado, andando, correndo, agachado e golpeando com espada (`default` e `wine_fox/18_wedding`):

![Parado](img/fase2-default-idle.png)
![Andando](img/fase2-default-walk.png)
![Golpe de espada](img/fase2-default-sword.png)

* Os adereços escondidos por `pre_parallel` (a espada gigante da Wedding) somem, a passada e o golpe animam.
* **A checar**: a direção do olhar da cabeça (no golpe a cabeça aparece inclinada; pode ser a animação ou o sinal do
  olhar), o agachar visto de lado, o piscar e a física de cabelo e saia em movimento (fotos paradas não mostram).
* Ainda não: itens nos locators (a espada não aparece na mão: fase 3), `ysm.bone_color`/`bone_transparency`/
  `bone_glow` no renderizador (`BoneAttributes` é calculado mas não aplicado), primeira pessoa, sono e a rotação de
  morte.

## Fase 2 (animação): runtime

Pacote `anim` (Java puro, testado). Ponto de entrada: `ModelAnimations.compile(loadedModel)` uma vez por modelo,
`new PlayerAnimator(anims)` uma vez por jogador, `update(inputs, ticksExisted + partialTicks)` a cada quadro e
`pose()` para o `GeometryRenderer`. As entradas vêm de `PlayerInputs` (cada getter diz o campo da 1.7.10;
`PlayerSnapshot` é uma implementação de campos simples).

| Upstream | Aqui |
|---|---|
| `geckolib3/core/keyframe/**`, `util/OrderedSegmentSearcher` | `Channel`, `Vec3Expr` |
| `format/parser/AnimationBuilder`, `model/resource/client/render/BakedAnimationMapper`, `RawBoneKeyFrame` | `ClipCompiler`, `AnimationClip` |
| `model/resource/client/AnimationStore`, `BakedAnimationSet`, `ExpressionSet` | `AnimationLibrary`, `DefaultAnimations` |
| `geckolib3/core/controller/AnimationPlayer`, `CodedAnimationController`, `transition/LinearBlendTransition` | `AnimationPlayer`, `CodedController`, `BoneQueue` |
| `geckolib3/core/processor/AnimationProcessor`, `snapshot/**`, `keyframe/AnimationVec3`, `util/MathUtil` | `AnimationProcessor`, `AnimMath` |
| `client/controller/collections/PlayerControllerCollection`, `client/animation/predicate/**` | `PlayerControllers` |
| `client/animation/AnimationRegister`, `PlayerMainPredicate` | `MainStates` |
| `client/animation/condition/**` | `ItemConditions` |
| `client/animation/physics/**` | `anim/physics` |
| `expression/host/builtin/{Entity,Model,ControlRead,Control,Player}Builtins`, `PositionComponent` | `PlayerMolangContext` |
| `format/schema/model/ModelOriginVersion` | `OriginVersion` |
| `client/entity/{Animatable,CustomHumanoid,CustomPlayer}Entity` (fluxo do quadro, olhar, emotes) | `PlayerAnimator` |

Igual ao upstream: amostragem (pre/post, linear, Catmull-Rom com a regra do Blockbench, segmento 0 segurando o `pre`
do primeiro quadro-chave), unidades (rotação em radianos com x e y negados, posição em pixels convertida para blocos com
x negado na pose, escalar para os três eixos), loop/uma vez/segurar no fim, transição de entrada por controlador (0,1 s
em main/hold/use/vehicle/passenger, 0 nos outros; escala sem transição de entrada nos controladores codificados),
transição de saída de 3 ticks (instantânea em ossos cuja animação tem escala), volta ao repouso em 3 ticks com nlerp de
quatérnio na rotação, `parallel*` somando rotação, `blend_weight`, mesma animação sem reinício, ordem dos controladores,
tabela de estados `main`, `$id`/`#tag`/`:classe`/ação de uso, `vehicle$`/`passenger$`, armadura `<slot>$`/`<slot>#`/
`<slot>:default`, regra de loop das mãos por `origin_ver`, fallback do modelo padrão (inclusive os `parallel*` e
`pre_parallel*` dele em modelos que não os têm), física `second_order`/`first_order` com o mesmo passo, olhar da cabeça
aplicado por código nos ossos `Head`/`HeadN` (2.x), piscar de `ysm.is_close_eyes`, `ctrl.*` e `query.*`.

Diferente, de propósito:

* `query.anim_time` vale o tempo da animação amostrada. No 2.x o contexto da animação é encerrado antes de o
  processador avaliar os quadros-chave, o que deixaria `anim_time` em 0 (modelos embutidos usam `q.anim_time` em
  quadros-chave). **A checar** contra o YSM 1.20 em jogo.
* Nomes `#tag` que não são resource locations válidos (nomes do OreDictionary, `ingotIron`) são aceitos como escritos;
  o upstream os descarta. Nomes de animação são lidos em ordem alfabética (a ordem das tags fica determinística).
* `second_order` com f = 0 e `first_order` com R = 0 não dividem por zero (o upstream fica congelado ou dá NaN).
  Valores não finitos de expressões viram 0 na pose.
* Um tempo de quadro que volta para trás (entidade nova) só re-ancora o relógio.
* Os efeitos `ysm.bone_color/transparency/glow` vão direto para `BoneAttributes` (sem lote de efeitos nem a
  distinção CONTEXT/ACTION); o `GeometryRenderer` ainda não os aplica.

Fora por enquanto: controladores Bedrock dos arquivos e os `pre_main`/`post_main`/`pre_hold`... que só existem quando
declarados (encaixe em `PlayerControllers`/`AnimationController`, fase 5), as funções `player_ctrl_*` que sobrepõem um
controlador codificado, sons de quadro-chave e `ysm.play_sound`, partículas, `ysm.sync`/`defer`, limitador de FPS da
animação, primeira pessoa (`fp_arm`), compatibilidades de mods (ParCool, TaCZ, SlashBlade, Carry On...). Testes: 72.

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
