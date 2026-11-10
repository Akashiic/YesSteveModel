# Etapa 6: nomes traduzidos dos arquivos `lang/` do modelo

A roleta e o seletor mostravam o texto do `ysm.json` (PORTING.md, fase 3b, "Ainda não"). Agora os textos vêm dos
arquivos de idioma do próprio modelo, no idioma do jogo, como no upstream. Também entrou a mensagem no chat ao tocar
pela roleta (`PRINT_ANIMATION_ROULETTE_MSG`).

## Upstream → aqui

| Upstream | Aqui |
|---|---|
| `format/parser/RawModelAssembler.writeLanguageFiles` (todo `.json` sob `files.language_path`, padrão `lang`, recursivo; locale = caminho sem `.json`; só valores string) | `model/ModelLang.read` (puro, testado) |
| `format/schema/model/views/ModelInfoView.translate` (locale atual → `en_us` → texto do `ysm.json`) | `ModelLang.translate`/`get` |
| `client/lang/LanguageManager.getI18n` | `ModelInfo.name/tips/authors/textureName(locale)`, `emote/RouletteText` |
| `client/gui/AnimationRouletteScreen` (`drawRouletteText`, `drawTooltips`, `addConfigForms`, `addRatioButtons`) | `emote/RouletteText` (puro, testado) + `client/gui/AnimationRouletteScreen` |
| `ModelRenderTarget` (`metadata.name`), `ModelInfoScreen` (`metadata.tips`), `button/AuthorButton`, `button/CatalogTextureButton` | `client/gui/ModelPickerScreen` |
| `ClientConfig.PRINT_ANIMATION_ROULETTE_MSG` (`PrintAnimationRouletteMsg`, padrão `false`), `clickDefault` | `print_animation_roulette_msg` em `config/futura3d/client.properties` (`client/ClientOptions`), padrão `false` |

Chaves (as do upstream):

| Chave | Onde aparece |
|---|---|
| `metadata.name` | nome do cartão, da prévia grande e da dica do seletor |
| `metadata.tips` | dica do cartão (inteira) e uma linha sob o nome da prévia grande |
| `metadata.authors.<n>.name` | autores (índice na lista do manifesto) |
| `files.player.texture.<nome>` | nome da textura sob a prévia grande |
| `properties.extra_animation.<chave>` | rótulo da fatia da roleta (vale também para `#id` de subpágina e para fatias com botão de configuração) |
| `properties.extra_animation.<chave>.desc` | dica ao passar o mouse sobre a fatia (só quando não vazia) |
| `properties.extra_animation.#<id>` | nome da subpágina na linha "Caminho" (a linha é só daqui) |
| `properties.extra_animation_buttons.<id>.config_forms.<n>.title` / `.description` / `.labels.<m>` | título, dica e opções de rádio dos formulários |

Ordem: o arquivo do idioma atual, se tiver a chave; senão `en_us`; senão o texto do `ysm.json` (para a fatia: o valor,
o nome do botão de configuração ou o índice quando em branco, como o upstream). O idioma da 1.7.10
(`gameSettings.language`, `en_US`, `pt_BR`) é passado para minúsculas, como os códigos da 1.20 e os nomes dos arquivos
(`pt_br.json`). A roleta e o seletor leem o idioma quando abrem: trocar o idioma no jogo vale na próxima abertura.

Os arquivos são lidos junto com o `ysm.json` no início (o seletor mostra nomes antes de carregar o modelo) e de novo
quando o modelo carrega (modelos do servidor: os arquivos `lang/*.json` já vêm na transferência; `net/ModelLimits`
aceita `.json`).

**Igual ao upstream**: as chaves, a pasta e o padrão `lang`, a ordem locale → `en_us` → `ysm.json`, valores não string
ignorados, a dica da fatia só com descrição não vazia, e o índice `<n>` dos formulários contando só os tipos que o
upstream mostra (checkbox, range, radio), o mesmo índice da rota de configuração dele; a mensagem no chat ao tocar pela
roleta (não pelas teclas extras) com a chave da animação, desligada por padrão.

**Diferente, de propósito**:

* Locales comparados sem diferença de maiúsculas (`PT_BR.json` vale para `pt_BR`); o upstream compara exato (na 1.20
  tanto o código quanto os arquivos já são minúsculos).
* Um arquivo de idioma quebrado (JSON inválido, não objeto, maior que 1 MB) é ignorado; no upstream a exceção derruba a
  montagem do modelo.
* O nome do modelo, dos autores e das texturas não aceita tradução em branco (fica o texto do `ysm.json`).
* A linha "Caminho" mostra os nomes das subpáginas traduzidos, e o painel de configuração tem uma linha de título (o
  rótulo traduzido da fatia que o abriu); o upstream não tem nenhum dos dois.
* Os textos do próprio mod (botões, "Licença", "Autores") continuam vindo de `assets/futura3d/lang/*.lang` (`en_US` e
  `pt_BR`); a nova chave `message.futura3d.roulette.play` está nos dois.

## Testes

* `model/ModelLangTest` (10): a troca de `en_US`/`pt_BR` para os nomes dos arquivos; os idiomas do `default` e da
  `wine_fox/14_momo`; `pt_br` sem arquivo cai no inglês, `zh_cn` tem o seu, chave que ninguém tem dá o `ysm.json`;
  chave ausente no idioma vem do `en_us`; leitura de memória com maiúsculas, outra pasta, BOM, arquivos quebrados e
  valores que não são string; modelo sem arquivos; nome em branco; as chaves; `ModelInfo` traduzindo nome, dica,
  autores e texturas; todo modelo embutido tem nome em `en_us` e `zh_cn`.
* `emote/RouletteTextTest` (3): rótulos do `default` em cada idioma e sem arquivos (nome do botão, índice); subpágina,
  formulários e chinês da Momo; descrição, dica em branco, contagem dos formulários com um tipo desconhecido no meio e
  opções de rádio.
* `client/ClientOptionsTest` (3, com a câmera): o arquivo de opções.

O arquivo da Momo usa `...config_forms.labels.<m>` sem o índice do formulário; com a chave do upstream
(`...config_forms.<n>.labels.<m>`) essas opções ficam com o texto do `ysm.json`, como no upstream (o teste confere).

## Resultado (harness)

`dev/RenderHarness.langSteps` (`futura3d-harness` com `only lang`), com a `wine_fox/14_momo` (o único modelo embutido
com subpágina, botão de configuração e textos em `zh_cn` diferentes do `ysm.json`). O idioma é trocado só em
`gameSettings.language` e na fonte Unicode (sem recarregar os recursos), então os textos do próprio mod ("Return",
"Lock", "Path") ficam em inglês nas fotos:

| Passo | Visto |
|---|---|
| `lang-roulette-zh` | rótulos do `zh_cn.json` (招手, 狐蹈~ em vermelho, 藏起来~, 狐狐敲级配件 com a engrenagem) |
| `lang-roulette-en` | os do `en_us.json` (Wave Hand, Fox Dance~, Hide & Seek~, Fox Super Config); o `ysm.json` tem os chineses |
| `lang-roulette-sub-en` | a subpágina `#H_d_c`: "Path: Fox Dance~" e os oito rótulos ingleses (Fox Shake, Dooble, Act Cute, Ciallo...) |
| `lang-roulette-config-pt` | `pt_BR` sem arquivo: inglês; painel com o título "Fox Super Config" e os formulários "Fox Size:", "Great Sword:", "Backpack Render", "Little Fox Toggle" |
| `lang-picker-zh-loaded` | seletor: nomes dos cartões em chinês (汉服酒狐, 小酒狐, 莫莫酒狐...), prévia grande com 莫莫酒狐 e a linha da dica, autores traduzidos, dica do cartão com a `metadata.tips` |
| `lang-picker-pt` | os mesmos em inglês (MoMo Wine Fox, "Welcome to the fox's world~", ZaiLaiJiJinXiaoMoMo) |

As fotos mostram modelos CC BY-NC-SA e ficaram fora do repositório (`run/client/screenshots/futura3d-lang-*.png`), pela
regra das imagens de `docs/img` (só modelos CC0).

## A checar

* Em jogo, com uma pessoa e o idioma trocado pelo menu (o harness só troca `gameSettings.language` e a fonte):
  rótulos longos em outras escalas de interface, e a dica da descrição (nenhum modelo embutido tem `.desc`; só testada
  em unidade).
* Um modelo de servidor com `lang/` numa subpasta (`lang/extra/pt_br.json` vira o locale `extra/pt_br`, como no
  upstream, e não casa com nenhum idioma).
* A mensagem no chat com a opção ligada (só a chave da animação, como o upstream; não o rótulo traduzido).
* Outros lugares com o nome do modelo: o log de carga e o comando do servidor (`/futura3d`) continuam com o nome do
  `ysm.json` (o servidor não sabe o idioma do jogador).
