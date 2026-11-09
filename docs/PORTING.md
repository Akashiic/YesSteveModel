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
| `network`, `capability`, `mixin`, `event`, `command`, `config` | ~14 mil | Reescritos para a 1.7.10 | **Rede e comandos feitos** na fase 4a (protocolo próprio, não o do upstream) |
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
  morte. Itens, atributos, sono e morte: feitos na fase 3a (abaixo).

## Fase 3a (terceira pessoa): resultado

Itens nos locators, atributos de osso, partições de face e os sinais visuais do jogo. Fotos do harness
(`dev/RenderHarness.itemSteps`, `futura3d-harness` com `items` roda só estes passos), cada uma ao lado do vanilla:

![Itens de frente: espada, arco puxado, vara lançada, bloco andando](img/fase3-itens-frente.png)
![Itens de lado, com os eixos do RightHandLocator e do Head lidos pela API](img/fase3-itens-lado.png)
![Dano, morte, sono, abóbora na cabeça, atributos de osso, invisível](img/fase3-sinais.png)

**Locators** (`geo/PoseMatrices`, puro e testado): a matriz de cada osso é calculada uma vez por quadro na CPU
(`T(pivô + posição) · Rz · Ry · Rx · S · T(-pivô)` encadeado) e serve às passadas de desenho e aos locators. O quadro de
um locator é a matriz do osso vezes `T(pivô)`, como `GeoModelState.visitLocatorGroup` do upstream; um grupo é todo osso
cujo nome sem os dígitos finais é o do grupo (`RightHandLocator`, `RightHandLocator2`...), e só ossos visíveis contam:
escala 0 num osso ou ancestral esconde o grupo, e o item com ele (o modelo `wine_fox/18_wedding` troca a espada da mão
pela própria, como o upstream decide em `attachments.md`).

**Itens** (`client/render/HeldItemRenderer`): o upstream põe o item em `locator · T(0, -1/16, -0.1) · Rx(-90°)` e chama o
`ItemInHandRenderer` da 1.20; o vanilla 1.20 usa `braço · Rx(-90°) · Ry(180°) · T(1/16, 0.125, -0.625)`. Igualando os
dois, o quadro de mão da 1.7.10 (`braço · T(-1/16, 7/16, 1/16)` de `RenderPlayer.renderEquippedItems`) fica
`locator · T(0, 1/8, 7/80) · Rz(180°)` (`geo/LocatorTransforms`, com teste), e dali seguem as transformações por item
da 1.7.10: blocos 3D, `IItemRenderer` `EQUIPPED` com `BLOCK_3D`, arco, ferramentas `isFull3D` (com a de bloquear),
itens planos, passadas múltiplas com cor, e a vara lançada desenhada como graveto, como o vanilla. Na pose de repouso
do modelo `default` a espada sai igual à do vanilla (conta feita em Python sobre a geometria); no jogo a pose de
segurar do modelo (`hold_mainhand:sword` gira o antebraço 30°) a deixa mais baixa e aberta, que é o que o modelo pede.
Capacete que não é armadura (bloco, abóbora, cabeça) vai no locator `Head`, como `CustomPlayerHeadLayer`; armadura não
é desenhada (o upstream também não). Os itens vêm depois do corpo, ou antes com `render_layers_first`.

**API para o Kunftig**: `PlayerModels.INSTANCE.locator(EntityPlayer, String)` devolve um `float[16]` (coluna principal,
ordem do `glMultMatrix`) do espaço local do locator (blocos, y para cima, origem no pivô animado, escala do modelo
incluída) para o espaço de render relativo à câmera (eixos do mundo, origem em `RenderManager.renderPosX/Y/Z`, o
espaço do `RenderWorldLastEvent`), ou `null` se o jogador não foi desenhado neste quadro nem no anterior ou o grupo
está escondido; `locators(...)` devolve o grupo inteiro. O quadro é guardado também para jogadores invisíveis, mas não
para o desenho no inventário. A mão esquerda: `PlayerModels.setOffhandSource(Function<EntityPlayer, ItemStack>)`, para
um mod como o Backhand dar o item, que vai no `LeftHandLocator` (a 1.7.10 vanilla não tem segunda mão).

**Partições** (`geo/FacePartition`, puro e testado, com os 24 modelos embutidos): a textura padrão é lida no
carregamento (`ImageIO`) e cada face é classificada pelo alfa sob o retângulo de UV: tudo 255 opaca, algum texel abaixo
de 255 (buracos inclusive) translúcida, tudo 0 fora. Corte de faces como o upstream decide: cubo completo (as seis faces
do arquivo) de tamanho positivo e todo opaco corta as costas; cubo completo de tamanho negativo sempre corta (o
enrolamento espelhado faz o corte esconder as faces da frente: a casca de contorno); o resto desenha os dois lados. Uma
display list por osso por partição. Opacas primeiro; as translúcidas em duas passadas, porque display list não ordena:
os texels totalmente opacos gravam profundidade, depois tudo é misturado com a máscara de profundidade desligada.
`all_cutout` trata toda face não vazia como opaca.

**Atributos de osso**: cor (vezes a do osso pai na chamada, não herdada), transparência só na partição translúcida
(`bone-opacity.md`), brilho 0..15 como luz de bloco e de céu (`-1` mantém a luz). Na foto: cabeça azul, alfa 80 em
todos os ossos (só o cabelo e um pedaço da perna somem), pernas com luz 0.

**Sinais**: o vermelho de dano e morte é a passada do `RendererLivingEntity` (modelo de novo, sem textura, onde a
profundidade foi gravada, cor `(brilho, 0, 0, 0,4)`). Invisível não desenha nada, nem itens, como o upstream (o vanilla
1.7.10 desenha o item na mão de um invisível). Na cama: o deslocamento de `RenderPlayer.renderLivingAt` e o giro de
`rotateCorpse` do vanilla, e a animação `sleep` deita o corpo (o upstream também mantém o giro da cama). Morte: sem a
inclinação do vanilla; o upstream zera `deathTime` antes de `setupRotations` e deixa a animação `death` fazer.

Desempenho no harness (llvmpipe, mesmas condições da fase 1, cópias na pose de repouso):

| Modelo | Quadro sem cópias | +1 | +10 | +30 |
|---|---|---|---|---|
| `default` | 25,9 ms | 32,6 ms | 41,1 ms | 46,6 ms |
| `wine_fox/18_wedding` | 25,1 ms | 35,8 ms | 56,5 ms | 98,7 ms |
| `wine_fox/08_sta` | 38,1 ms | 45,9 ms | 119,5 ms | 234,8 ms |

Entre 10 e 30% mais lento que a fase 2 nos modelos grandes com 10 e 30 cópias: faces que antes eram cortadas agora
têm os dois lados (como o upstream decide), as translúcidas passam duas vezes e há mais display lists. Trocar
`GL_NORMALIZE` por `GL_RESCALE_NORMAL` não mudou nada mensurável.

**A checar**:

* Em jogo, lado a lado com o YSM 1.20: a posição do item na mão (a conta das transformações é exata, mas as
  transformações por item da 1.7.10 não são as da 1.20), o bloco na cabeça (o upstream usa o contexto `HEAD` da 1.20)
  e a cabeça de jogador (`Items.skull`), que não foi fotografada.
* A segunda mão com o Backhand: o encaixe existe, mas o item usa a mesma transformação da mão direita, sem espelhar.
* ~~A linha da vara de pescar sai da mão do bíped vanilla (`RenderFish`), não do locator~~: feito na fase 3c.
* Ordem de translúcidos entre si sem ordenação (o upstream ordena de trás para frente por desenho); `force_translucent`
  e o significado exato de `all_cutout` (o código nativo não está no repositório).
* As normais das faces de trás que ficam visíveis (casca de contorno, faces de dois lados) não são invertidas como o
  nativo faz: a luz delas pode sair mais escura.
* A animação `death` é de tocar uma vez: passado 1 s ela volta à pose de repouso; no jogo a entidade some aos 20 ticks,
  mas um mod que segure o jogador morto mais tempo veria isso.
* Desempenho com Angelica e numa placa de vídeo de verdade.

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
  distinção CONTEXT/ACTION); o `GeometryRenderer` os aplica desde a fase 3a.

Fora por enquanto: controladores Bedrock dos arquivos e os `pre_main`/`post_main`/`pre_hold`... que só existem quando
declarados (encaixe em `PlayerControllers`/`AnimationController`, fase 5), as funções `player_ctrl_*` que sobrepõem um
controlador codificado, sons de quadro-chave e `ysm.play_sound`, partículas, `ysm.sync`/`defer`, limitador de FPS da
animação, primeira pessoa (`fp_arm`, feita na fase 3b), compatibilidades de mods (ParCool, TaCZ, SlashBlade, Carry
On...). Testes: 72.

## Fase 3b: primeira pessoa e animações extras (emotes)

### Braços em primeira pessoa

| Upstream | Aqui |
|---|---|
| `client/controller/collections/FPArmControllerCollection`, `client/animation/predicate/FPArmArmorPredicate` | `anim/FirstPersonControllers` |
| `client/entity/CustomFirstPersonArmEntity` (animador próprio, `v.roaming` projetado do corpo) | `PlayerAnimator.firstPerson`, `MolangVariables(MolangVariables)` |
| `client/entity/FirstPersonModelState` (raízes `LeftArm`/`RightArm`/`Background`, subárvore sem os ancestrais) | `ModelAnimations.compileFirstPerson`, `GeometryRenderer.drawSubtree` |
| `client/renderer/CustomFirstPersonArmRenderer`, `client/event/ReplacePlayerHandRenderEvent`, `RenderFirstPlayerBackground` | `client/FirstPersonArms`, `mixin/mixins/RenderPlayerArmMixin`, `ItemRendererMixin` |

O braço em primeira pessoa é a geometria `arm` do modelo com um segundo `PlayerAnimator` do mesmo jogador, só com
os controladores do upstream para os braços: `fp.arm.parallel_N` (os `parallelN` do conjunto `fp_arm`, somando
rotação, com o `fp_arm` do modelo padrão por trás) e `fp.arm.armor_<slot>`. Não há main, hold, use nem swing nos
braços: o conjunto `arm` do modelo, apesar do nome, tem as animações de mão da terceira pessoa (ossos `UpBody`,
`Head`...), e o upstream não o usa na primeira pessoa. O braço se move com as transformações da própria 1.7.10
(balanço da câmera, `equippedProgress`, golpe). As variáveis `v.roaming.*` do braço são as do corpo; as outras são
dele.

No jogo, `RenderPlayer.renderFirstPersonArm` (que a 1.7.10 chama com a mão vazia e, duas vezes, com um mapa) é
trocado pela subárvore `RightArm`, na mesma matriz e com o mesmo deslocamento do upstream (`translate(-0,25, 1,8, 0)`,
`scale(-1, -1, 1)`). A 1.7.10 só desenha o braço direito, então `LeftArm` não aparece (no mapa os dois braços são o
direito, como na vanilla). Itens na mão ficam onde a vanilla os põe, como no upstream (que também só troca o braço sem
item). O osso `Background`, quando existe, é desenhado na pose de repouso depois do balanço do braço, com o balanço da
câmera desfeito e 1,5 bloco abaixo, como `RenderFirstPlayerBackground`; nenhum modelo embutido tem esse osso.

![Primeira pessoa, parado](img/fase3b-default-fp-idle.png)
![Primeira pessoa, golpe com a mão vazia](img/fase3b-default-fp-swing.png)

Harness (`firstPersonSteps`): parado, vanilla, andando, golpe com a mão vazia, golpe vanilla, espada (a vanilla
desenha só o item), `wine_fox/19_nine_tailed` (o `parallel0` dele anima ossos do braço). O braço do modelo fica no
lugar do vanilla, um pouco mais fino (é a geometria do modelo) e acompanha o golpe e o balanço de andar.

* **A checar** em jogo: o `Background` (sem modelo embutido para testar; o desfazer do balanço usa os campos da 1.7.10
  e não inclui o `cameraPitch`), o braço no mapa, a luz do braço sob o sol e no escuro, Angelica.
* A câmera não se move: no upstream, `ViewLocator` e `AllHead` só servem à compatibilidade com o mod First Person
  Model (corpo visível em primeira pessoa e altura dos olhos). Na 1.7.10 a câmera fica na altura vanilla.
* Fase 5: o `fp.arm.misc` e os `fp.arm.parallel_*` declarados em arquivos de controladores do braço (o upstream só os
  cria quando o modelo os declara; entram primeiro na lista de `FirstPersonControllers`).

### Animações extras (emotes)

| Upstream | Aqui |
|---|---|
| `client/gui/AnimationRouletteScreen` (estado: caminho, páginas, cliques) | `emote/RouletteMenu` (sem Minecraft, testado) |
| `client/gui/AnimationRouletteScreen` (desenho), `button/FlatColorButton` | `client/gui/AnimationRouletteScreen` |
| `client/input/AnimationRouletteKey`, `ExtraAnimationKey`, `client/event/PlayerMoveEvent` | `client/EmoteKeys` |
| `ClientProtocolGateway.playSelfAnimation`/`stopSelfAnimation` | `client/LocalEmotes`, `emote/EmoteListener` |

Teclas (categoria "3D Futura Engine"): roleta em `Z` (como no upstream e no YSM antigo), animações extras 0 a 7 sem
tecla, trava da roleta sem tecla (no upstream é Alt+L; a 1.7.10 não tem modificadores). A roleta mostra as entradas de
`properties.extra_animation` (chave = animação, valor = rótulo; valor em branco mostra o índice), oito por página,
as subpáginas `#id` de `extra_animation_classify` em vermelho, `#return` volta um nível (fecha na raiz), no máximo
6 níveis no caminho, e a página aberta é lembrada por modelo. As teclas extras 0-7 endereçam as entradas da raiz
(`#return` para o emote, `#id` abre a roleta na subpágina) e não tocam nada com o jogador andando. Andar, pular ou
agachar para o emote, a não ser com a trava ligada (botão no centro da roleta ou a tecla).

![Emote extra0](img/fase3b-default-emote.png)
![Roleta](img/fase3b-default-roulette.png)

Seam para a rede (fase 4): os emotes por enquanto são só locais. `LocalEmotes.play`/`stop` tocam no animador do
jogador local e avisam os `EmoteListener` registrados com `LocalEmotes.addListener`; a camada de rede registra um que
manda o pedido ao servidor, e cada cliente que recebe o repasse chama
`PlayerModels.INSTANCE.animator(player).playExtra(name)`/`stopExtra()` para aquele jogador.

Ainda não:

* `extra_animation_buttons` com `config_forms`: o anel interno (engrenagem) abre um painel que só lista os formulários;
  editar (`checkbox`/`range`/`radio` em `v.roaming`) e sincronizar fica para depois, com a rede.
* Os rótulos e descrições traduzidos dos arquivos `lang` do modelo (`properties.extra_animation.<chave>`) e a dica com
  a descrição: aparece o texto do `ysm.json`.
* Mensagem no chat ao tocar (opção `PRINT_ANIMATION_ROULETTE_MSG`).
* **A checar** em jogo: a roleta com outras escalas de interface, roda do mouse, modelos com várias páginas e
  subpáginas (`wine_fox/08_sta`).

Testes: 182 (31 do braço em primeira pessoa, incluindo todos os modelos embutidos; 6 da roleta; 2 do `v.roaming`
compartilhado).

## Fase 3c: API para outros mods, linha de pesca, varas e lâminas do Kunftig

### API estável (`api/FuturaModels`)

`com.akashic.futura.api.FuturaModels` é o ponto de entrada para outros mods, só com tipos do JDK e do Minecraft nas
assinaturas, para ser chamado por reflexão (o Kunftig não depende do futura3d). Lado do cliente; no servidor dedicado
responde `false`/`null`. `API_VERSION = 2` (o 2, da fase 4b, acrescenta `modelKey`).

| Método | O que dá |
|---|---|
| `isModelled(player)` | desenhado no mundo com modelo neste quadro ou no anterior |
| `modelKey(player)` | o modelo com que o jogador é desenhado agora (`builtin:<pasta>` ou `server:<id>`; `builtin:default` enquanto o dele carrega). Cada jogador tem o seu, então os locators diferem de um para outro |
| `locator(player, nome)`, `locatorOrNull(player, nomes...)`, `locators(player, nome)` | quadro do locator em espaço de render relativo à câmera (o de `RenderWorldLastEvent`); pedido antes de o jogador ser desenhado neste quadro (uma entidade desenhada antes, como a boia), a pose do quadro anterior é levada junto com o jogador para onde ele está agora |
| `hasLocator(player, nome)` | o modelo tem um osso do grupo, visível ou escondido (escala 0) |
| `drewModel(player)`, `locatorInLastDraw(player, nome)` | para quem trata `RenderPlayerEvent.Post`: se o desenho que acabou foi o modelo, e o locator relativo à matriz desse evento (mundo ou inventário). `RenderPlayerEvent.Specials` não dispara para jogador com modelo |
| `setKeepsItemWhileFishing(Predicate)` | um mod que desenha a própria vara (o Kunftig) mantém o item na mão com a linha fora, no lugar do graveto |

### Linha de pesca

`mixin/mixins/RenderFishMixin` + `client/FishingLine`: com o pescador desenhado com modelo (e fora da primeira pessoa
dele mesmo), a linha vanilla sai da ponta do graveto que o modelo segura no `RightHandLocator`
(`LocatorTransforms.castRodTip`: o fim do ícone do graveto pelas transformações da 1.7.10, 0,77 bloco à frente da mão
e 0,17 acima, com teste). Só os `addVertex` da linha são redirecionados (a curva é a vanilla); o `startDrawing` fica
livre, e é nele que o `RenderFishLineMixin` do Kunftig cancela o desenho para pôr a linha na ponta da vara 3D. Assim
os dois compõem em qualquer ordem de aplicação dos mixins: com vara 3D e ponta capturada, a linha do Kunftig; senão, a
linha vanilla saindo do modelo.

O upstream **não** faz isso: `FishingHookRendererReplace` mantém os deslocamentos fixos do vanilla (só soma o
agachar) e a linha não segue a mão do modelo. Aqui segue, porque na 1.7.10 esses deslocamentos ficam longe da mão.

### Resultado (harness do Kunftig, modo `models`, com o jar de dev do futura3d em `run/client/mods`)

![Vara vanilla lançada vista de lado: a linha sai do graveto na mão do modelo](img/fase3c-linha-vanilla.png)

* Vara vanilla lançada: graveto na mão do modelo e a linha saindo da ponta dele, de frente e de lado, nos modelos
  `default` e `wine_fox/13_matured`.
* Vara 3D do Kunftig (pack do AkashicFishing): desenhada na mão do modelo pelo `IItemRenderer` `EQUIPPED`, continua
  na mão com a linha fora (o `Predicate` acima), e a linha dourada sai da ponta capturada pelo Kunftig.
* Lâmina do Kunftig (estilo SlashBlade) nos locators `BladeLocator`/`SheathLocator`/`LeftWaistLocator`: ver o Kunftig,
  `docs/playermodel/ESTUDO-YSM.md`, seção 10.
* `isModelled` falso em primeira pessoa do jogador local, verdadeiro em terceira e para outro jogador.

**A checar**: em jogo de verdade (não no harness) a linha de outro jogador num servidor, com a boia desenhada antes
dele (o caminho do quadro anterior); a linha com o jogador dormindo ou montado; a cor da linha (vanilla preta).

Testes: 226.

## Fase 4a: servidor, protocolo e transferência de modelos

O servidor (dedicado, ou o integrado do singleplayer, pelo mesmo caminho) lê modelos abertos de
`config/futura3d/models/`, manda o catálogo a quem tem o mod, transfere os arquivos que o cliente não tem no cache,
guarda o modelo e a textura de cada jogador por UUID e repassa emotes. Cliente vanilla não recebe nada; cliente com o
mod num servidor sem ele manda um hello que ninguém responde e fica só com as escolhas locais.

### Canal

Um canal FML orientado a eventos, `futura3d:model` (14 caracteres; o limite do Bukkit 1.7 é 16), em `ModNetwork`.
Por que FML e não `S3FPacketCustomPayload`/`C17PacketCustomPayload` crus: o `NetworkDispatcher` do FML fica no
pipeline netty da conexão no Forge, no Thermos e no Crucible, e entrega os pacotes de canais registrados ao mod antes do
handler vanilla (que o Thermos reescreve para alimentar o plugin messaging do Bukkit), nos dois sentidos; o C17 cru
precisaria de um gancho em `NetHandlerPlayServer.processVanilla250Packet`, justamente o método que o Thermos troca. Um
`FMLProxyPacket` não tem prioridade, então vai para a fila da `NetworkManager` e é tratado na thread principal (do
servidor ou do cliente): nada de travas.

O cliente só fala depois de entrar no mundo (primeiro tick com `getNetHandler()`), com um hello; o servidor só manda
algo a quem mandou hello com a mesma versão. Servidor sem o mod ignora o canal desconhecido (vanilla, Forge e o
Messenger do Bukkit fazem isso).

### Protocolo (`net/`, Java puro, testado)

Cada payload: byte de tipo, depois os campos em `DataOutput` (big endian); string = tamanho em bytes (u16) + UTF-8
estrito; string anulável = byte 0/1 + string; hash = 32 bytes crus; UUID = dois longs. Nunca serialização Java, nunca
Molang ou código para executar (os arquivos do modelo são dados, lidos pelo carregador de sempre).

| Tipo | Sentido | Campos |
|---|---|---|
| 1 `S_HELLO` | S→C | versão, revisão do catálogo, nº de modelos, UUID do jogador no servidor |
| 2 `S_MODEL` | S→C | revisão, id, nome, dica, licença, autores, flags (restrito, bloqueado para este jogador), arquivos (caminho, tamanho, sha256) |
| 3 `S_CATALOG_END` | S→C | revisão: o catálogo novo substitui o anterior |
| 4 `S_CHUNK` | S→C | hash, tamanho total, offset, dados (≤ 30 656 bytes, payload < 30 KiB) |
| 5 `S_UNAVAILABLE` | S→C | hash que o servidor não tem mais |
| 6 `S_STATE` | S→C | até 96 × (UUID, chave ou nulo, textura ou nulo) |
| 7 `S_EMOTE` | S→C | UUID, animação ou nulo (parar) |
| 64 `C_HELLO` | C→S | versão |
| 65 `C_REQUEST` | C→S | 1 a 256 hashes |
| 66 `C_SELECT` | C→S | chave ou nulo, textura ou nulo, `initial` (a escolha local gravada, mandada ao entrar) |
| 67 `C_EMOTE` | C→S | animação ou nulo |

* Versão `Protocol.VERSION = 1`. Os dois hellos começam sempre com tipo + versão: uma versão futura reconhece a
  diferença. Versões diferentes: o servidor responde só com o próprio hello e o cliente fica com o local.
* Chaves: `builtin:<pasta>` (o cliente tem; nunca é transferido) e `server:<id>` (64 hex). Id do modelo = SHA-256 da
  lista canônica de arquivos (caminho UTF-8, byte 0, tamanho, hash, em ordem de caminho): dois servidores com os mesmos
  arquivos dão o mesmo id, e o cliente confere o id contra a lista.
* O hello traz o UUID do jogador no servidor: num servidor em modo offline (comum com Thermos) ele é diferente do UUID
  da sessão do cliente, e o cliente traduz estados e emotes desse UUID para o jogador local. Achado no teste com o
  servidor dedicado: sem isso o próprio jogador nunca trocava de modelo.
* `C_SELECT` com `initial`: o cliente manda `ModelSelection.localKey()`/`localTexture()` ao entrar; o servidor só usa
  se não tem nada gravado para o jogador (nem a escolha dele, nem a de um operador).

Limites conferidos na decodificação, antes de alocar: payload ≤ 32 767 bytes e nada sobrando no fim, tipo conhecido e
do lado certo, ≤ 1024 modelos, ≤ 512 arquivos por modelo, caminho ≤ 256 bytes, limpo (`ModelSource.clean` não muda
nada, sem `..`, `//`, `\`, `/` no início ou fim) e sem repetição, `ysm.json` presente, arquivo ≤ 16 MiB, modelo ≤ 64 MiB,
catálogo ≤ 1 GiB baixado, nome ≤ 256 bytes, dica ≤ 2048, licença ≤ 128, ≤ 16 autores de ≤ 128, chave ≤ 128 e com forma
válida, textura e animação ≤ 128 sem caracteres de controle, chunk dentro do arquivo. Depois de 32 erros de protocolo de
um lado, as mensagens dele passam a ser ignoradas.

### Repositório do servidor (`server/ModelRepository`)

`config/futura3d/models/<nome>/` ou `<nome>.zip` (qualquer um seleciona) e `config/futura3d/models/auth/...`
(restritos: precisa de permissão). Formato aberto, `ysm.json` spec 2; um zip com tudo dentro de uma pasta é lido dessa
pasta. Qualquer outra coisa é pulada com uma linha no log, `.ysm` inclusive ("encrypted .ysm exports are not
supported"); nomes com espaço, nomes repetidos e modelos com os mesmos arquivos de outro também.

Cada modelo é lido para a memória com limites (`net/ModelLimits`, configuráveis em `config/futura3d/server.properties`):
só arquivos `json png jpg jpeg webp avif ogg molang lang txt` (o resto, como `.bbmodel`, fica fora), ocultos e
`__MACOSX` fora, links simbólicos não seguidos, profundidade 16, ≤ 512 arquivos, ≤ 16 MiB por arquivo contando o que é
lido de verdade (bomba de zip para no limite), ≤ 32 MiB por modelo, ≤ 4096 entradas de zip olhadas, entrada de zip que
sai do modelo recusa o modelo (`ModelSource.clean`); PNG e JPEG ≤ 4096 px de lado pelo cabeçalho, sem decodificar; JSON
percorrido em streaming (profundidade ≤ 64, strings ≤ 64 Ki caracteres, onde moram as expressões Molang, ≤ 4 M tokens)
antes do Gson; texto (`.molang`) ≤ 256 KiB. Depois o modelo é carregado de verdade com `model/ModelLoader` e conferido:
≤ 4096 ossos, ≤ 32 768 cubos, ≤ 4096 animações. A entrada do catálogo tem de caber numa mensagem (< 30 KiB).
Memória total ≤ 512 MiB. Os 24 modelos embutidos passam nos limites padrão (teste).

`/futura3d reload` relê numa thread própria; o tick seguinte troca os modelos, limpa as filas de envio, manda o
catálogo novo a todos e reenvia os estados (o id muda quando os arquivos mudam; a escolha gravada segue o modelo pelo
nome).

### Seleção, permissões e comandos

`config/futura3d/players/<uuid>.json` (escrito num temporário e movido no lugar; arquivo ilegível vira `.bad`):
`builtin` ou `model` (nome), `texture`, `forced`, `grants`. Restaurado ao entrar e mandado a todos com o mod (o estado
de todo jogador online vai para todo cliente com o mod, não só para quem o rastreia: assim quem chega perto já sabe).
Textura que o modelo não tem vira a padrão. Escolha inválida do cliente: o servidor devolve só a ele o estado atual.

A permissão está num método só, `ModelServer.canUse`: restrito precisa de grant. Plugins de permissão do Bukkit ficam
fora (no Thermos/Crucible a ponte iria ali).

| Comando (nível 2) | O que faz |
|---|---|
| `/futura3d list` | modelos, arquivos, bytes, id, texturas; `[auth]` nos restritos |
| `/futura3d reload` | relê os modelos sem reiniciar |
| `/futura3d set <jogador> <modelo\|default\|builtin:<pasta>> [textura]` | escolha do operador, vale mesmo restrito sem grant; jogador offline por nome ou UUID |
| `/futura3d auth <jogador> add\|remove <modelo>` | grant; reenvia o catálogo (o `locked` muda) |
| `/futura3d play <jogador> <animação\|stop>` | emote para quem o vê e para ele |

Modelo por nome da pasta/zip ou pelo começo do id (≥ 8 hex). Taxa por jogador: 1 seleção/s (rajada 4), 2 emotes/s
(rajada 6).

### Transferência e cache do cliente

O servidor manda o catálogo inteiro a cada jogador (modelos bloqueados também: ele precisa desenhá-los nos outros), com
o `locked` dele. O cliente (`net/ClientSession`, puro) pede só os hashes que não estão em
`<mcDataDir>/futura3d/cache/<sha256>` (`net/DiskFileCache`: grava num temporário e move, confere o hash a cada
leitura e apaga o arquivo corrompido), no máximo 16 arquivos ou 8 MiB pendentes por vez. O servidor (`TransferQueue`)
manda os pedidos em ordem, em pedaços desde o offset 0, com balde de fichas por jogador (`bytesPerSecondPerPlayer`,
padrão 512 KiB/s). O cliente remonta com o tamanho do catálogo, recusa pedaço fora de ordem, confere o SHA-256, grava,
e quando todos os arquivos de um modelo estão lá confere os arquivos de novo por tipo (`ModelLimits.checkFiles`, numa
thread própria) e registra `ModelRegistry.registerServerModel("server:" + id, new CachedModelSource(...), locked)`
(`net/CachedModelSource`, que lê do cache conferindo o hash). Catálogo novo: `clearServerModels()` e os modelos voltam
do cache. Ao sair: `setHandler(null)`, `clearServerModels()`, `clearRemote()`, ouvinte de emotes removido.

Estados chegam em `ModelSelection.select`; enquanto o servidor tem o mod, o handler manda a escolha do seletor ao
servidor (que valida e devolve o estado). Emotes: o `EmoteListener` manda play/stop; o servidor repassa a quem rastreia
o jogador; o cliente chama `PlayerModels.animator(jogador).playExtra/stopExtra`.

| Arquivo | |
|---|---|
| `ModNetwork` | canal FML, pacote ↔ bytes |
| `net/Protocol`, `Message`, `MessageCodec`, `Sha256`, `FileRef`, `CatalogEntry` | protocolo |
| `net/ClientSession`, `FileCache`, `DiskFileCache`, `CachedModelSource`, `ModelLimits` | lado do cliente sem Minecraft, limites comuns |
| `server/ModelRepository`, `ServerModel`, `PlayerStore`, `TransferQueue`, `BuiltinModels`, `ModelServer` | lado do servidor sem Minecraft |
| `server/ForgeModelServer`, `FuturaCommand` | cola com o Forge (só classes comuns: o dedicado carrega) |
| `client/net/ClientNetwork` | cola do cliente |
| `dev/ConnectHarness` | harness: `connect <host>:<porta> [modelo]` em `run/client/futura3d-harness` entra num servidor dedicado |

### Resultado

Servidor dedicado (`./gradlew runServer`, Java 8, modo offline, `config/futura3d/models/futura_test` = cópia do
embutido CC0 `misc/1_alex`, `auth/steve` = cópia de `misc/2_steve`, e um `encrypted.ysm` falso) e um cliente
(`runClient -Pharness`, modo `connect`), pelo caminho de rede de verdade (o singleplayer passa os pacotes como objetos,
sem serializar):

![Servidor dedicado: /futura3d set troca o jogador para o modelo do servidor](img/fase4a-servidor-dedicado.png)
![/futura3d play: o emote chega ao próprio jogador](img/fase4a-emote-servidor.png)

* Log do servidor: `Skipped encrypted.ysm: encrypted .ysm exports are not supported`, `Loaded futura_test: Alex (...,
  11 files, 326123 bytes)`, `Loaded auth/steve`.
* Cliente com cache vazio: catálogo de 2 modelos, 641 553 bytes baixados em 1 a 2 s (o limite padrão é 512 KiB/s), 19 arquivos
  no cache (os avatares repetidos entre os dois modelos vão uma vez só), os dois modelos registrados e carregados pelo
  `ModelRegistry`. `/futura3d set` → a chave do jogador local vira `server:c0b19563...` e ele aparece com o modelo;
  `/futura3d play ... extra0` → `isPlayingExtra()` verdadeiro, `stop` → falso; `auth add/remove` e `reload` reenviam
  o catálogo e os modelos voltam do cache sem baixar de novo.
* `./gradlew runServer21` (Java 21.0.11 com lwjgl3ify e Hodgepodge): o dedicado sobe, lê os modelos e não carrega
  classe de cliente. O cliente de dev não entra nele (o servidor exige o gtnhlib que o cliente não tem), então o
  caminho de rede foi testado com o servidor Java 8.
* Singleplayer (harness, `only server`): os passos `server-*` trocam o jogador para o modelo do servidor integrado
  pelo mesmo caminho.
* Uma das conexões do cliente de dev ao dedicado ficou parada em "Connecting" sem rastro no servidor; repetida, entrou.

**A checar**:

* Num Thermos/Crucible de verdade com Java 21: o canal FML convivendo com plugins Bukkit (o registro `REGISTER` do
  canal, plugins que mexem em plugin messages), o modo offline e o online.
* Vários jogadores ao mesmo tempo: troca de modelo e emote de outro jogador vistos ao vivo (os testes de unidade
  cobrem com clientes falsos; no jogo só houve um jogador). Quem chega perto no meio de um emote não o vê (o repasse é
  só para quem já rastreia).
* Links lentos e muitos jogadores baixando juntos: não há limite global, só por jogador; os pedaços disputam a conexão
  com os pacotes do jogo.
* Cliente vanilla e cliente com o mod em servidor sem o mod (pelo código, nada é mandado a quem não manda hello e o
  hello cai num canal desconhecido; não testado com um cliente vanilla de verdade).
* Pastas de modelos grandes: a leitura do início é síncrona (o reload não); um servidor com centenas de modelos atrasa
  a partida. O cache do cliente não tem limite de tamanho nem limpeza.
* A escolha pelo seletor no jogo indo ao servidor por clique (o handler e o `C_SELECT` estão testados com clientes
  falsos; no harness a troca foi pelo comando).

Testes: 313 (60 desta fase: codec e limites, sessão do cliente, cache em disco, limites com os 24 embutidos,
repositório com pastas, zips, zip slip, bomba de zip e links, gravação por UUID, fila com limite de taxa e o servidor
falando com sessões de cliente reais pelo codec).

## Fase 4b: vários modelos no cliente e o seletor

Cada jogador é desenhado com o seu modelo. A camada de rede (fase 4a, outro trabalho: pacotes `net` e `server`) só
fala com dois contratos do cliente, `client/ModelRegistry` e `client/ModelSelection`; esta fase os implementa, junto
com o desenho por jogador, as fontes de modelo, as texturas que não estão nos pacotes de recursos e o seletor.

### Modelos conhecidos (`ModelRegistry`)

* Chaves `builtin:<pasta>` para cada modelo embutido e `server:<id>` para os que o servidor mandar. Os embutidos são
  listados em `assets/futura3d/builtin/index.txt` (um jar não se lista de forma portátil; `ModelInfoTest` confere que
  o índice tem todas as pastas com `ysm.json`), na ordem do seletor: `default`, `misc/*`, `wine_fox/*`.
* No início só os manifestos são lidos (`model/ModelInfo`: nome, autores, licença, texturas, `preview_animation`);
  `builtin:default`, o modelo de reserva, é carregado na hora. Os outros carregam no primeiro uso (alguém o escolhe, o
  seletor o mostra) num executor de duas threads de baixa prioridade: leitura, `GeoBaker`, compilação das animações
  (terceira e primeira pessoa), decodificação das texturas e partições de face. O trabalho de GL (display lists,
  envio da textura) fica para o primeiro desenho, na thread de render (`client/ClientModel`).
* `registerServerModel(chave, fonte, travado)` carrega fora da thread de render e substitui a chave (o modelo antigo
  continua desenhado até o novo ficar pronto, e então é liberado). Enquanto um modelo não está pronto, quem o escolheu
  aparece com `builtin:default`.
* Memória: `clearServerModels()` libera display lists e texturas dos modelos do servidor e esquece os animadores e
  quadros deles; ao fechar o seletor, os embutidos que ele carregou e que ninguém usa nem escolheu são liberados
  (`releaseUnused`) e carregam de novo quando preciso.

### Seleção (`ModelSelection`)

`select(uuid, chave, textura)`, `keyOf`, `textureOf`, `clearRemote()` e `setHandler`, como o contrato. Sem handler
(um jogador, ou servidor sem o mod), `requestLocal` aplica a escolha ao jogador local e a grava em
**`config/futura3d-client.properties`** (`model=` e `texture=`; `requestLocal(null, null)` apaga o arquivo), que
volta depois de reiniciar o jogo; uma seleção que o servidor mande para o jogador local vale mais que ela. Com
handler, `requestLocal` só chama o handler. `localKey()`/`localTexture()` dão a escolha gravada para a rede mandar ao
entrar num servidor.

### Desenho por jogador

`PlayerModels` pede a `ModelSelection` a chave de cada jogador (`builtin:default` quando nula ou não pronta), escolhe a
textura (`texture` = nome do arquivo sem extensão entre as do modelo, senão a padrão do modelo) e mantém um
`PlayerAnimator` por jogador **e modelo**: quando o modelo do jogador muda, o animador antigo é descartado. Os quadros
dos locators (`ThirdPersonRenderer`) também são por jogador e trocados quando a geometria muda, então
`FuturaModels.locator` e companhia continuam valendo por jogador. Os braços em primeira pessoa usam o modelo e a
textura do jogador local. `PlayerModels.use(pasta)`/`force(chave)` ficaram só para o harness (todos com o mesmo modelo).

### Fontes e texturas

* `model/FolderModelSource` (pasta; recusa caminho que saia dela, inclusive por link simbólico), `ZipModelSource`
  (lido para a memória uma vez; limites de tamanho por entrada, total e número de entradas contados nos bytes
  descomprimidos, não nos cabeçalhos; entrada com `..` ou caminho absoluto recusa o zip inteiro; um zip com tudo
  dentro de uma pasta é lido dessa pasta) e `MemoryModelSource` (`Map<String, byte[]>`), todos com
  `ModelSource.clean`. Testes em `ModelSourcesTest`.
* Texturas de modelos fora do classpath vêm dos bytes da fonte (`ImageIO`), viram `client/render/ModelTexture`
  (como o `DynamicTexture`, mas larga os pixels depois de enviar) registrada no `TextureManager` como
  `futura3d:dynamic/model_<n>`, e são apagadas com o modelo; o local liberado é reaproveitado pela próxima textura,
  porque o `TextureManager` da 1.7.10 nunca esquece um local. A **mesma imagem** dá as partições de face. Cada textura
  tem as suas partições (opaca/translúcida vêm do alfa dela); texturas com partições iguais dividem as display lists.
  Os embutidos continuam com a textura pelo pacote de recursos.

### Seletor (`client/gui/ModelPickerScreen`)

Tecla **`Y`** (como no YSM antigo), na categoria "3D Futura Engine", ao lado da roleta. À direita, os modelos em
cartões (5 x 2 por página, menos colunas em tela estreita; páginas por botões e roda do mouse) com uma prévia 3D
animada, o nome, a licença numa etiqueta colorida (verde CC0, ciano licenças livres, laranja não comercial, vermelho
reservada ou desconhecida), `S` nos modelos do servidor, os travados escurecidos com "Bloqueado", o em uso com borda
verde. A dica do cartão mostra nome, chave, licença com a descrição, autores e número de texturas. À esquerda, a
prévia grande do cartão escolhido, girando (arrastar gira à mão; `disable_preview_rotation` para o giro), o seletor de
textura, a licença em destaque numa faixa e os autores; o botão confirma com `ModelSelection.requestLocal` (clique duplo
no cartão também). Os cartões tocam a `preview_animation` do modelo; a prévia grande toca o idle, como a tela de
texturas do upstream. O enquadramento é o do upstream (`geo/ModelFraming`, de `GuiModelView`/`GuiModelFraming`): a
caixa dos ossos visíveis na pose, num envelope que só cresce, ocupando 88% do retângulo. Textos em `en_US` e `pt_BR`.

![Seletor (só os modelos CC0, filtro do harness): Default com a textura azul, dica do cartão Alex](img/fase4b-seletor.png)
![Modelos como o servidor mandaria: Default Boy de uma pasta (textura vermelha) e Alex da memória, texturas enviadas pelo caminho dinâmico](img/fase4b-servidor.png)
![O seletor com os modelos do servidor: o travado escurecido, a dica dele](img/fase4b-seletor-servidor.png)

Harness (`dev/RenderHarness.pickerSteps`, `futura3d-harness` com `only picker`): seletor aberto, seletor com os
modelos carregados e a dica, só CC0, a segunda página (poses de GUI autorais e a segunda textura da Momo), `requestLocal`
de `builtin:wine_fox/18_wedding` (o jogador local muda, a escolha é gravada), dois jogadores com modelos diferentes
(Wedding e Default azul), três modelos registrados como o servidor faria (pasta, memória e um travado, cópias de
modelos CC0 embutidos), o seletor com eles, e a volta ao `builtin:default` depois de `clearServerModels`. No fim,
`requestLocal(null, null)` apaga a escolha gravada.

**A checar**:

* Lado a lado com o YSM 1.20: as poses de GUI autorais (`preview_animation: gui` dos `wine_fox` mostra uma moldura
  `Backgrounds` atrás do personagem, que no harness sai cinza clara e grande no cartão: o enquadramento inclui a
  moldura, como o do upstream, mas a cor e a proporção não foram comparadas), o ângulo da câmera dos cartões
  (aqui de frente para pose autoral e a 20° para idle) e a luz da prévia.
* Com a rede (fase 4a): o handler, `locked` vindo do servidor, a troca de modelo de outro jogador ao vivo e
  `clearServerModels` ao desconectar; aqui só foram exercitados pelo harness, sem rede. Com a fase 4a, o registro
  dos modelos do servidor, `clearServerModels` a cada catálogo novo e a troca do próprio jogador foram vistos com um
  servidor dedicado; a troca de outro jogador ao vivo segue a checar.
* Tempo de carga fora do harness (aqui, de 10 a 360 ms por modelo nas threads de carga, a maior parte na primeira
  carga, com o JIT frio) e o custo do seletor com dez prévias animadas numa máquina fraca.
* Nomes CJK e com códigos `§` nos cartões em outras escalas de interface; um zip de verdade arrastado por um usuário
  (o carregamento local de pastas e zips ainda não tem tela: as fontes existem para a rede e para a fase seguinte).

Testes: 253 (fontes de modelo, `ModelInfo` e o índice dos embutidos, `ModelFraming`, a escolha gravada e o contrato de
`ModelSelection` sem o jogo).

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
