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
* A linha da vara de pescar sai da mão do bíped vanilla (`RenderFish`), não do locator: fica para o Kunftig, que vai
  usar a API.
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
