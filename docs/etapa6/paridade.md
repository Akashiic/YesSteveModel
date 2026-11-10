# Etapa 6, item 7: validação e comparação com o YSM 1.20

**O que esta etapa conseguiu e o que não conseguiu.** A comparação lado a lado no jogo pede um cliente Minecraft 1.20.1
com o YSM 3.0 (o jar e a biblioteca nativa oficial dele) e uma placa de vídeo. Neste ambiente não há nem um nem outro:
a biblioteca nativa completa do upstream exige a cadeia do Conan com 26 pacotes próprios e o MSVC no Windows, e a
renderização aqui é por software (llvmpipe). Então a paridade desta etapa foi conferida **contra o código do
upstream**, não contra o upstream rodando, e o lado a lado fica como roteiro abaixo, para quem tiver os dois jogos.

## O que foi comparado com o código do upstream

| Área | Fonte do upstream | Como foi conferido aqui |
|---|---|---|
| `.ysm` v1/v2 | YesSteveModel-Native `modules/legacy/src/bundle.cc` | Porte linha a linha; pacotes escritos pelo inverso exato voltam byte a byte |
| `.ysm` v3, envelope e contêiner | `modules/legacy/src/v3/{codec,container}` | **O próprio código do upstream** (copiado sem mudança); o vetor congelado dele passa no teste nativo e por JNI |
| `.ysm` v3 → modelo | `modules/legacy/src/v3/conversion/projector.cc` | Mesmos campos, nomes de conjunto (`main`, `fp_arm`...), ordenações (`UnsignedUtf8Less`), `extra0..7` padrão, regra de ordem dos substitutos (≤ 26); o quad sintético volta idêntico |
| Geometria assada | `format/parser/GeoBuilder` (Java) e `gfx/src/bake/baked_model.cc` (`ProcessCube`) | `GeoBaker` já era porte; o `poly_mesh` reproduz `cubes_legacy`; o teste de cubo invertido é o do nativo |
| Partições, corte, ordem dos translúcidos | `gfx/src/bake/baked_model.cc`, `gfx/src/renderer/*` | Regras portadas do código (não da documentação, que diverge em espessura zero); corte na CPU provado equivalente ao do GL em 3000 casos; chave de profundidade NDC e desempate na ordem de emissão |
| Opus | `natives/sound/OpusDecoder` (libopus) | Concentus contra o libopus do ffmpeg nos arquivos da Momo: mesmas contagens de amostras, diferença máxima de 5 e 9 LSB (~70 dB) |
| Projéteis e veículos | `client/controller/...`, `client/renderer/...Replace...`, `format/parser/*` | Pilhas de controladores, predicados (inclusive o `air` em voo), matrizes de posicionamento portadas; diferenças de propósito listadas no documento do item |
| Câmera (`ViewLocator`) | compatibilidade com o First Person Model | Mesma regra de pivô (`ViewLocator`, senão `Head`, 24 px = olho 1,62); o upstream não move a câmera, aqui move (pedido desta etapa), só no desenho |
| Nomes traduzidos | `ModelInfoView.translate` e as chaves do `lang/` | Mesmas chaves e mesma ordem de queda (idioma → `en_us` → `ysm.json`), contagem de formulários só checkbox/range/radio |

Além disso, todos os 24 modelos embutidos passam pelos testes que já existiam (carregar, animar 600 quadros corpo e
braços sem NaN, controladores, funções, sons) e pelos novos (alvos de projétil/veículo animando 180 quadros, partições
por modelo, ordenação em 8 vistas, idiomas). Testes: 534 ao fim desta etapa.

## Roteiro do lado a lado

Mesmo modelo nos dois jogos (os embutidos CC0 `default`, `misc/1_alex`, `misc/3_default_boy`,
`misc/4_default_controllers`; e, com as licenças respeitadas, os `wine_fox`), mesma janela (854x480 ou 1280x720), mesma
distância e ângulo de câmera. Os passos do harness (`dev/RenderHarness`: `only <grupo>` em
`run/client/futura3d-harness`) já fotografam o lado da 1.7.10; no 1.20, reproduzir à mão a mesma pose.

| # | O quê | Lado 1.7.10 | Comparar |
|---|---|---|---|
| 1 | Poses básicas: parado, andando, correndo, agachado, golpe | passos `default-*`, `wedding-*` | ângulos dos ossos, piscar, física do cabelo em movimento |
| 2 | Itens nos locators: espada, arco puxado, vara, bloco na cabeça | `only items` | posição e escala do item na mão (a conta é exata, as transformações por item são as da 1.7.10) |
| 3 | Primeira pessoa e altura da câmera | `only fp`, `only view` | braço; altura dos olhos agachado e com `v.player_size` (o upstream não move a câmera: comparar com o First Person Model) |
| 4 | Translúcidos | `only transl` | véu e auréola do `wedding`, cabelo do `little`, círculos do `magical` |
| 5 | Controladores e sons | `only ctrl` | estados (`player.post_hold`, `post_swing`), sons da Saint e da Momo (Opus) |
| 6 | Emotes, roleta e formulários | `only config`, `only lang` | rótulos traduzidos, formulários da Momo e da Sta |
| 7 | Projéteis e veículos | `only repl` | flecha, boia, barco, carrinho (o do `default` aparece deslocado no trilho), carro do `16_tactics` |
| 8 | `.ysm` legado | servidor com o arquivo em `config/futura3d/models/` | o mesmo `.ysm` aberto pelo YSM 1.20 (que o importa para o contêiner dele) |

Itens de cada área que ficaram **a checar** estão no fim de cada documento desta etapa (`ysm-legado.md`, `opus.md`,
`projeteis-veiculos.md`, `view-locator.md`, `nomes-traduzidos.md`, `translucidos.md`) e nas fases anteriores do
`PORTING.md`. Os que pesam mais na paridade:

* `query.anim_time` com o valor da animação amostrada (fase 2: o upstream 2.x deixaria 0) e o `defer` sem restaurar o
  contexto da chamada (fase 5a).
* A posição do item na mão e o bloco na cabeça (fase 3a).
* A pose das lâminas do SlashBlade (fase 5b).
* O carrinho do `default` e a altura do jogador sentado (item 2).
* As faces de UV de tamanho zero com as UVs arredondadas (item 6).
* Um `.ysm` v3 real (item 1).
