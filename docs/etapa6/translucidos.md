# Etapa 6 (item 6): partições do nativo e ordem dos translúcidos

Fonte do upstream: o renderizador nativo (YesSteveModel-Native, Apache-2.0, `modules/gfx`), que não está no repositório
do mod: `src/bake/baked_model.cc` (tipos de quad, partições, cubo invertido), `src/renderer/render_state_impl.inc`
(coeficiente de face), `src/renderer/cube/transform_common_impl.inc` (corte, normal, profundidade),
`src/renderer/buffer/translucent.h` e `quad_ref.h` (ordenação), `src/renderer/render_impl.inc` e `render.cc` (desenho).
Do lado Java do upstream: `ModelRenderTargetLoader`/`BuiltinModelMaterializer` (opções do bake), `ModelProperties`
(`all_cutout`), `IGeoRenderer`/`CustomTranslucentRenderType` (render type).

Código aqui: `geo/FacePartition` (puro), `geo/TranslucentSort` (puro, novo), `client/render/GeometryRenderer`,
`client/ClientModel`; passos `transl-*` no `dev/RenderHarness`.

## Partições (`geo/FacePartition`)

O Java do upstream chama o bake sempre com `origin_ver = 29` (`CURRENT_RAW_UV_VERSION`), `force_culling =
properties.all_cutout` e `force_translucent = false`. Aqui, as mesmas regras, linha a linha do `baked_model.cc`:

**Tipo de cada quad** (`QuadInspector::Process`, versão de UV `k254`): as UVs são arredondadas para texels (u sobe a
partir de 0,6 de texel, v a partir de 0,4) e essas UVs arredondadas são as desenhadas. Um retângulo sem largura ganha
um texel à direita (ou à esquerda, na borda, movendo u dos vértices 0 e 3); sem altura, ganha o texel de cima se houver
(movendo v dos vértices 2 e 3 um texel para cima) ou o de baixo na linha 0. Os texels sob o retângulo dão o tipo:
todos com alfa 0 vazio (fora), algum alfa entre 1 e 254 translúcido, só 0 e 255 recorte (`cutout`), todos 255 sólido.

| | Antes (fase 3a) | Agora (= nativo) |
|---|---|---|
| Amostragem | texel conta se o centro está dentro; UVs intactas | arredonda como o nativo e desenha as UVs arredondadas |
| Recorte (0 e 255) | translúcida | translúcida (igual) |
| Partição translúcida | sem corte, exceto cubo de tamanho negativo nos 3 eixos | sem corte, exceto cubo invertido ou `all_cutout` |
| Cubo invertido | só tamanho negativo nos 3 eixos | seis faces e alguma normal apontando para o centro do cubo: 1 ou 3 eixos negativos, 2 eixos negativos, espelho de osso (`mirror` do osso troca as faces leste/oeste sem trocar a normal) |
| Corte das opacas | cubo completo, tamanho positivo, seis faces opacas | seis faces sólidas, qualquer sinal ou espessura zero; ou cubo invertido; ou `all_cutout` |
| `all_cutout` | toda face não vazia vira opaca | `force_culling`: todo cubo de seis faces corta as costas, nas duas partições (o nome engana: no upstream é `@SerializedName("all_cutout") forceCulling`) |
| Face ausente (UV por face) | não corta | não corta (igual) |

Diferenças que mudam a imagem: modelos com espessura zero (planos de cabelo, saia) e seis faces sólidas agora cortam,
como o nativo: topo e base coincidentes deixam de brigar no z (cada um é visto só do seu lado). Cubos com espelho de
osso ou um/dois eixos negativos agora cortam. A documentação do upstream (`architecture/rendering/overview.md`) diz que
espessura zero e espelho em um ou dois eixos "não ganham" corte; o código nativo dá, e aqui vale o código.
`force_translucent` não foi portado (o Java do upstream nunca liga; a doc de problemas conhecidos do upstream diz que
ligar exigiria rever a ordenação).

UVs mexidas pelo arredondamento (mais de 1/100 de texel), por modelo embutido: default 76, alex 56, steve 58, default_boy 64,
controllers 4, taisho_maid 6, new_year 4, astronaut 0, kongfu 0, magical 66, hanfu 10, jk 0, sta 251, salesperson 0,
little 168, matured 363, momo 0, kluonoa 11, tactics 826, wedding 14, nine_tailed 6, survivor 375, saint 206, elf 180.
Quase todas são faces de UV com tamanho zero (o texel extra) ou UVs fracionárias.

Distribuição nos modelos embutidos (quads; textura padrão):

| Modelo | quads | `cutout` | `cutout_no_culling` | `translucent` | `translucent_culling` |
|---|---|---|---|---|---|
| `default` | 1032 | 852 | 63 | 11 | 0 |
| `misc/1_alex` | 282 | 96 | 0 | 33 | 0 |
| `misc/2_steve` | 276 | 60 | 55 | 18 | 0 |
| `wine_fox/05_magical` | 9255 | 7032 | 1344 | 157 | 0 |
| `wine_fox/08_sta` | 26564 | 23004 | 1540 | 556 | 0 |
| `wine_fox/12_little` | 2442 | 1368 | 689 | 117 | 0 |
| `wine_fox/16_tactics` | 3772 | 2813 | 430 | 264 | 0 |
| `wine_fox/18_wedding` | 6817 | 5700 | 97 | 810 | 0 |
| `wine_fox/20_survivor` | 7232 | 4170 | 2029 | 171 | 0 |
| `wine_fox/21_saint` | 4035 | 3805 | 115 | 7 | 2 |

(O resto está na saída do teste `FacePartitionTest.everyBuiltinModelPartitionsWithItsTexture`.)

## Corte (culling)

O nativo corta na CPU: `facing = (normal, d) · olho × sinal_do_enrolamento`, face de trás se `facing <= 0`, com o
"olho" homogêneo tirado dos cofatores das linhas x, y e w da matriz modelo→clip (`ComputeFacingCoefficient`). Isso é
exatamente o teste de enrolamento do GL (frente anti-horária na tela), também para projeção ortográfica (o olho vira
uma direção no infinito) e para matrizes espelhadas. O teste `TranslucentSortTest.backFacesAreTheOnesOpenGlCulls`
confere em 3000 casos aleatórios (perspectiva, ortográfica, a ortográfica de y invertido das telas do Minecraft com o
`scale(-s, s, s)` do inventário, espelhos) que a conta da CPU dá o mesmo sinal da área na tela. Por isso:

* **Opacas**: continuam em display lists com `GL_CULL_FACE` (mesmo resultado, mais rápido).
* **Translúcidas**: corte na CPU, com a conta do nativo (as das duas partições translúcidas vão numa lista só, que o GL
  não poderia cortar pela metade).

Diferente de propósito: o nativo para depois de `quad_count_after_culling` faces da frente por cubo (3, ou 5 num cubo
invertido). É uma otimização para cubo convexo visto de fora; com a câmera dentro de uma casca invertida o nativo perde
uma das seis faces. Aqui todas as faces da frente são desenhadas.

## Normais das faces de trás

O nativo inverte a normal de toda face de trás que desenha (só existem nas partições sem corte). Aqui:
`GL_LIGHT_MODEL_TWO_SIDE` ligado a partir da passada `cutout_no_culling` até o fim do desenho do modelo (e da
sobreposição de dano); na passada `cutout` não precisa, as costas já são cortadas pelo mesmo teste. Com o
`glColorMaterial(GL_FRONT_AND_BACK, ...)` que a 1.7.10 já usa (`RenderHelper`), o GL ilumina as faces que viram as
costas para a tela com a normal invertida, que são as mesmas que o nativo marca (mesmo teste de enrolamento). Vale para
as display lists e para o caminho da CPU; por isso a CPU não inverte a normal (inverteria duas vezes). Estado
restaurado com `GL_LIGHTING_BIT`.

## Ordem dos translúcidos (`geo/TranslucentSort`)

O que o nativo faz (`TranslucentProxyBuffer`): numa chamada de desenho de um modelo, todos os quads das duas partições
translúcidas vão para um buffer à parte; cada quad leva a chave `NdcDepthSortKey(clip_z / clip_w)` do seu centro (média
dos quatro vértices, levado por `clip · osso`), e o buffer é ordenado como `uint64` (chave nos 32 bits altos, posição
de emissão nos baixos): do mais fundo para o mais próximo, empate na ordem de emissão (`translucent` antes de
`translucent_culling`, ossos em pré-ordem, cubos, faces). Granularidade: **por quad**. Profundidades não finitas vão
para o fim. Entre chamadas diferentes (outros jogadores, outros modelos) não há ordenação, lá nem aqui. A sombra do Iris
não ordena (não se aplica aqui).

Aqui é igual: a CPU leva os quatro vértices de cada quad translúcido pela matriz do osso (a de `geo/PoseMatrices`, já
calculada por quadro), descarta as faces de trás de `translucent_culling`, calcula a mesma chave sobre `projeção ·
model-view` (lidas com `glGetFloat` uma vez por desenho) e ordena um `long[]` com a chave invertida no bit de sinal e o
índice de emissão. Ossos ocultos (escala zero, subárvore da primeira pessoa) ficam fora.

Diferenças pequenas: a pré-ordem segue a `BakedGeometry` (raízes e filhos na ordem dela, que pode diferir da do arquivo
em hierarquias declaradas fora de ordem): só muda empates exatos entre ossos. O centro é transformado em `float` Java
(mesma conta, outra ordem de operações).

## Desenho (`client/render/GeometryRenderer`)

| | Antes | Agora |
|---|---|---|
| Opacas | display lists por osso e partição | igual |
| Translúcidas | display lists, duas passadas sem ordenar: texels opacos gravando profundidade, depois mistura com a máscara de profundidade desligada | CPU: transforma, corta, ordena de trás para frente e manda pelo `Tessellator` numa passada: mistura `SRC_ALPHA, ONE_MINUS_SRC_ALPHA`, gravando profundidade, alfa > 0,1 (o `entityTranslucent` do upstream grava profundidade e descarta alfa < 0,1) |
| Cor, transparência, brilho do osso | por chamada de lista | por vértice no caminho da CPU (transparência só nas translúcidas, brilho como coordenada de lightmap) |
| Sobreposição de dano (`drawFlat`) | todas as listas com `GL_EQUAL` | opacas pelas listas; translúcidas pelo mesmo caminho da CPU (os mesmos floats, então `GL_EQUAL` bate). Como as translúcidas agora gravam profundidade em todo texel visível (antes só nos de alfa 255), o vermelho pega nelas inteiras, como no upstream (lá a sobreposição é por vértice) |
| Telas (inventário, seletor) | mesmo desenho | mesmo desenho, a conta do corte vale para a ortográfica |

API pública mantida (`GeometryRenderer(geometry)`, `(geometry, byte[][])`, `draw`, `drawSubtree`, `drawFlat`,
`delete`); novo construtor `(geometry, FacePartition.Baked)` com as UVs arredondadas. `FacePartition.classify` mantém a
assinatura; `FacePartition.bake` devolve partições e UVs. `GeometryRenderer.sortTranslucent` (estático, padrão `true`)
é um interruptor de desenvolvimento: `false` desenha as translúcidas na ordem fixa de emissão, para fotos de
comparação.

## Desempenho

Ordenação na CPU (teste de unidade, 8 vistas por modelo, JVM do Gradle, primeira execução): 0,08 ms para os 810 quads
do `wedding`, 0,36 ms para os 556 do `sta` (os primeiros modelos medidos pagam o aquecimento do JIT; os pequenos ficam
em 0,01 a 0,04 ms). Os modelos embutidos têm no máximo 810 quads translúcidos, contra milhares de opacos, então as
display lists continuam carregando quase tudo.

Harness (`copySteps`, llvmpipe, cópias na pose de repouso; média do quadro e, entre barras, a média do desenho das
cópias na CPU). As duas rodadas foram na mesma sessão, com o código da fase 3a ("antes", partições e listas antigas) e
com o desta etapa ("depois"), mas a máquina estava dividida com outros agentes (carga média 4 a 8 em 4 núcleos na
rodada "antes", 3,5 a 5 na "depois"): o ruído é grande (o `wedding` sem cópias deu 62 ms numa e 32 na outra).

| Modelo (quads translúcidos) | | +1 | +10 | +30 |
|---|---|---|---|---|
| `default` (11) | antes | 28,3 / 0,27 ms | 31,4 / 2,2 ms | 44,4 / 10,7 ms |
| | depois | 27,1 / 0,33 ms | 33,0 / 2,2 ms | 36,3 / 6,5 ms |
| `wine_fox/18_wedding` (810) | antes | 63,7 / 3,9 ms | 68,8 / 22,3 ms | 85,7 / 51,0 ms |
| | depois | 33,8 / 1,5 ms | 53,2 / 17,4 ms | 94,3 / 55,2 ms |
| `wine_fox/08_sta` (556) | antes | 39,7 / 5,8 ms | 111,6 / 61,1 ms | 218,5 / 173,0 ms |
| | depois | 46,6 / 9,1 ms | 94,8 / 54,2 ms | 215,6 / 173,6 ms |

Leitura honesta: dentro do ruído, sem perda no `sta`; no `wedding` com 30 cópias (24 300 quads translúcidos
ordenados e reenviados por quadro) o desenho ficou cerca de 8% mais caro, com a rodada "depois" na máquina menos
carregada. Esta rodada "depois" tinha a iluminação de dois lados ligada em todas as passadas; a versão final só a liga
depois da passada `cutout` (onde as costas já são cortadas e o resultado é o mesmo), o que não foi medido de novo.

## Testes

* `FacePartitionTest`: arredondamento (`quantize`) e tipos de quad; o texel extra dos retângulos degenerados e o
  deslocamento das UVs; cubo invertido (3 eixos, 1 eixo, 2 eixos, espessura zero, espelho de cubo e de osso, rotação);
  regras de partição (seis sólidas, buraco, face vazia, `all_cutout`, invertido, espessura zero, face ausente); todos
  os 24 modelos embutidos partem com a sua textura (com a distribuição e as UVs mexidas na saída).
* `TranslucentSortTest`: corte da CPU igual ao do GL em 3000 casos; chave de profundidade (ordem, -0, NaN, infinito);
  dois painéis e um cubo em ossos diferentes trocam de ordem conforme o lado da câmera, e ossos ocultos saem; a casca
  invertida translúcida mostra só as 3 faces do fundo, todas de frente na tela; empate (topo e base de um plano)
  mantém a ordem de emissão vista de cima e de baixo; matriz de normal com escala e espelho; os 18 modelos `wine_fox`
  ordenam em 8 vistas (profundidade não crescente, nenhuma face de trás cortável desenhada, normais unitárias).

**Fotos** (`only transl`, passos `transl-wedding-*` e `transl-little-*`, FOV 30, cada vista com a ordenação e com
`sortTranslucent = false`; mais uma rodada com o código antigo). Não estão no repositório: os dois modelos são CC
BY-NC-SA e as fotos em `docs/img` só mostram modelos CC0. O que se vê:

* Véu do `wedding` de costas: antes (duas passadas sem ordem) os painéis sobrepostos se misturam em qualquer ordem e
  as bordas dos de trás aparecem por cima dos da frente; depois, os painéis da frente cobrem os de trás de forma
  coerente. Na mesma passada sem ordenar (o interruptor) aparecem buracos: painéis de trás desenhados depois dos da
  frente são rejeitados pela profundidade. É o defeito que a ordenação resolve.
* Auréola do `wedding` (`ysmGlowHalo`, um quad translúcido virado para cima, visto de baixo): antes clara (a normal
  da frente), depois mais escura, porque a face de trás passa a ser iluminada com a normal invertida, como o nativo
  faz. Mudança esperada, mas a conferir ao lado do YSM 1.20.
* `little` (máscara, cabelo): sem diferença visível nessas vistas.
* Seletor de modelos (projeção ortográfica, `picker-page2`): os modelos com translúcidos (a máscara da Momo) saem
  normais.

**A checar**:

* Em jogo de verdade, lado a lado com o YSM 1.20: véu e auréola do `wedding`, máscara e cabelo do `little`, círculos
  do `magical`, a casca de contorno de algum modelo de terceiros (nenhum embutido tem cubo invertido translúcido além
  de 2 quads do `saint`).
* As UVs arredondadas nas faces de UV de tamanho zero (o texel de cima, como o nativo): conferir que não aparecem listras
  novas em modelos de terceiros.
* Iluminação de dois lados com Angelica e com pacotes de shader (eles ignoram a iluminação fixa; as faces de trás
  ficariam com a normal da frente, como antes desta etapa).
* A sobreposição de dano agora também pinta as faces translúcidas (como o upstream): conferir em jogo.
* Translúcidos de jogadores diferentes continuam sem ordem entre si (como no upstream).
* Desempenho com muitos jogadores de modelos com centenas de quads translúcidos numa placa de vídeo de verdade (o
  `Tessellator` reenvia esses vértices a cada quadro).
