# Etapa 6, item 3: sons Ogg Opus (Concentus)

Até aqui os sons Ogg Opus dos modelos eram pulados com uma linha no log (`Skipped sound ... Ogg Opus`, ver
`docs/PORTING.md`, fase 5a, "Quadros-chave de instrução e sons"): a 1.7.10 só decodifica Vorbis (JOrbis). O conversor
do upstream grava Opus, e a Momo embutida (`wine_fox/14_momo`: `fly_on`, `sfmb`, `dooble`, `owo`) já é Opus. Agora
eles tocam como os Vorbis. O upstream decodifica Opus na biblioteca nativa (`natives/sound/OpusDecoder`; o
`AudioNormalizer` é o transcodificador da importação); aqui é Java puro, com o Concentus.

## Dependência

* **Concentus** 1.0.2, Maven Central `io.github.jaredmdobson:concentus:1.0.2` (SHA-1 `3658ef53df76c1fb635617c03320cd56dfdd49f3`):
  port em Java puro da libopus (decodificador, codificador, multistream), sem contêiner Ogg. É o fork publicado do
  [lostromb/concentus](https://github.com/lostromb/concentus); o POM aponta para a licença desse repositório.
* Licença: BSD de 3 cláusulas (termos da Opus/Concentus; detentores Skype, Xiph.Org, CSIRO, Microsoft, Jean-Marc Valin,
  Gregory Maxwell, Mark Borgerding, Timothy B. Terriberry, Logan Stromberg). O texto vai em
  `src/main/resources/licenses/concentus` (o mesmo arquivo que o upstream distribui; vai dentro do jar, como pede a
  cláusula de distribuição binária) e o `NOTICE.md` lista a biblioteca.
* Bytecode: as 137 classes do jar têm versão de classe 52 (Java 8), conferido no cabeçalho de cada `.class`; o POM
  compila com `source/target 8`. Nenhuma API além do Java 8.
* Empacotamento pelo suporte de shadow do buildscript GTNH: `usesShadowedDependencies = true` e
  `relocateShadowedDependencies = true` em `gradle.properties`, `shadowImplementation(...)` em `dependencies.gradle`.
  O `ShadowModule` do gtnhgradle 2.0.33 põe o prefixo `<modGroup>.shadow` com realocação automática, então o Concentus
  vai para `com.akashic.futura.shadow.io.github.jaredmdobson.concentus` e não colide com cópia de outro mod.
  `minimizeShadowedDependencies` ficou `false` (o Concentus inteiro tem ~800 KB; o jar já tem dezenas de MB de modelos).
* Efeito no build: o `jar` vira `-dev-preshadow`, o `shadowJar` faz o `-dev`, e o `reobfJar` (o jar de release) parte
  do `shadowJar`. O `shadowJar` lê as classes compiladas, não a saída do `jar`, então a exclusão do harness
  (`com/akashic/futura/dev/**`, sem `-Pharness`) foi estendida a ele em `build.gradle.kts`.
* Conferido no jar de release (`build/libs/futura3d-*.jar` sem `-dev`): 137 classes em
  `com/akashic/futura/shadow/io/github/jaredmdobson/concentus/`, nenhuma em `io/`, `licenses/concentus` presente,
  nenhuma classe de `com/akashic/futura/dev/`. E o decodificador do próprio jar de release, rodado fora do jogo,
  decodifica os sons da Momo pelas classes realocadas.
* Em desenvolvimento (`runClient`, testes) o Concentus está no classpath com o nome original; só o jar é realocado.

## Decodificador puro (`com.akashic.futura.audio`, sem Minecraft)

* `OggPacketReader` (RFC 3533): confere o padrão `OggS`, a versão 0 e o **CRC** de cada página (um arquivo corrompido
  falha com `IOException`, não com áudio estragado), junta pacotes que atravessam páginas (laço de 255), dá a cada
  pacote a posição granular da página quando é o último a terminar nela, e marca o último pacote do fluxo (página com
  fim de fluxo). Páginas de outro fluxo lógico são puladas; num arquivo encadeado toca só o primeiro elo. Um pacote
  cujo começo nunca chegou é descartado, como faz a libogg. Pacotes acima de 16 MiB são recusados.
* `OggOpusDecoder` (RFC 7845): lê `OpusHead` (versão, canais, pre-skip, taxa de entrada informativa, ganho de saída
  Q7.8, família de mapeamento) e `OpusTags`; decodifica um pacote por `read` com o Concentus em PCM 16 bits a 48 kHz.
  Ganho de saída aplicado pelo decodificador (`setGain`), pre-skip descartado no começo, fim cortado na posição
  granular da última página. Família 0: mono e estéreo como estão. Família 1 (até 8 canais, ordem Vorbis): decodificada
  com o `OpusMSDecoder` e mixada para estéreo (frentes no seu lado, centro nos dois a -3 dB, surround/traseiras no seu
  lado a -3 dB, centro traseiro a -6 dB, LFE fora, normalizado para não saturar). Outras famílias (255 etc.): recusadas.
  Só o pacote corrente fica em memória.
* Arquivo cortado no meio de uma página: `IOException("truncated Ogg page")`. Cortado entre páginas (sem a página de fim
  de fluxo): termina mais cedo, sem erro.

## No jogo (`client/CodecOggOpus`, `client/ModelSounds`)

* Caminho escolhido: um codec do paulscode. `CodecOggOpus` implementa `ICodec` sobre o `OggOpusDecoder` e é registrado
  para a extensão `opus` (`SoundSystemConfig.setCodec`, em `ModelSounds.register`). A 1.7.10 registra só `ogg` →
  `CodecJOrbis` (no `SoundManager`); o `CodecWav` está no classpath mas não é registrado, e decodificar para WAV em
  memória perderia o streaming. O paulscode escolhe o codec pela expressão `.*[oO][pP][uU][sS]` sobre o identificador.
* `ModelSounds`: o identificador do som termina em `.opus` para Opus e `.ogg` para Vorbis; o resto não mudou: fonte
  posicional seguindo o jogador (atenuação linear, `16 × max(volume, 1)`), volume × "Jogadores", pitch 0,5–2, limites
  6 por jogador e 16 ao todo, e a regra dos 256 KiB (até 256 KiB o paulscode decodifica tudo com `readAll` e guarda;
  maior toca em streaming com `read`, um buffer de streaming por vez, ~1,4 s de mono a 48 kHz com o padrão de 128 KiB;
  no laço o paulscode reabre a URL `futura3d:`). Dos quatro da Momo, `fly_on` (6,4 KiB) é decodificado e guardado; os
  outros três (0,7–1,5 MB, até 174 s) tocam em streaming, sem os ~8–16 MB de PCM inteiro na memória.
* Os cabeçalhos Opus são conferidos ao ler o arquivo (`OggOpusDecoder.probe`): um Opus que o codec não toca (família
  255, cabeçalho ruim) é pulado com `Skipped sound ... Ogg Opus not playable (...)`, em vez de falhar na thread do
  paulscode. Se o registro do codec falhar, os Opus voltam a ser pulados (`codec not registered`).
* Um fluxo danificado no meio toca o que decodificou e termina (mensagem no logger do paulscode). A saída é sempre
  little-endian; `reverseByteOrder` é ignorado, como no `CodecJOrbis` (o `LibraryLWJGLOpenAL` pede `true` e o JOrbis
  também ignora).
* Estéreo continua não posicional no OpenAL, como nos Vorbis estéreo (o OpenAL só espacializa mono). Os quatro da Momo
  são mono.
* `ysm.play_sound` e os `sound_effects` não mudaram.

## Verificação

* Testes (405 no total no `./gradlew spotlessApply build`, todos passando):
  * `audio/OggOpusDecoderTest`: os quatro Opus da Momo decodificam sem exceção, mono, família 0, pre-skip lido igual ao
    do cabeçalho, e com **exatamente** `última posição granular − pre-skip` amostras (achada varrendo o arquivo de
    trás, sem o leitor); fixtures geradas com ffmpeg/libopus (`src/test/resources/futura3d/audio`: estéreo com 440 Hz
    à esquerda e 660 Hz à direita, 0,5 s; 5.1 família 1, 330 Hz, 0,3 s) conferem a ordem dos canais e a mixagem pelos
    cruzamentos de zero; arquivo cortado no meio de página (`IOException` "truncated"), cortado entre páginas (termina
    antes), página corrompida (CRC), Vorbis/lixo/vazio recusados, família 255 recusada, pacote atravessando duas páginas.
  * `client/CodecOggOpusTest`: o codec como o paulscode o usa: achado pela extensão (e não para `.ogg`), `readAll` do
    `fly_on` pela URL `futura3d:` (formato 48 kHz mono 16 bits little-endian, tamanho exato, não silêncio), `read` em
    blocos do `dooble` duas vezes (laço: `initialize` de novo na mesma URL) com o total exato, e arquivo cortado ou lixo
    sem exceção.
* Contra a libopus de referência (fora dos testes): `fly_on` e `sfmb` decodificados pelo jar de release e pelo ffmpeg
  com `-c:a libopus` dão o mesmo número de amostras (32127 e 4800845) e diferença máxima de 5 e 9 LSB (SNR ~70 dB; o
  Concentus é o caminho de ponto fixo da libopus).
* Pelo paulscode de verdade, sem Minecraft: um programa avulso subiu o `SoundSystem` com `LibraryLWJGLOpenAL` (natives
  LWJGL 2 do `run/natives`, OpenAL Soft com `drivers = wave` gravando um WAV), registrou JOrbis e `CodecOggOpus` e tocou
  como o `ModelSounds`: `fly_on` com `newSource`, `dooble` com `newStreamingSource`, `atk1` (Vorbis) com `newSource`,
  pitch 1,5. Sem erro no log do paulscode; `playing` verdadeiro e depois falso para `fly_on` (0,68 s / 1,5) e `atk1`, e
  o `dooble` tocando até o fim do programa; o WAV gravado tem áudio no começo e energia contínua depois que só o
  `dooble` restava. Só o `fly_on`: ~0,5 s de áudio no WAV.
* **Harness não rodado**: os assets do Minecraft não estão no cache desta máquina (o `runClient` baixaria tudo) e falta
  o `xrandr`; a máquina estava dividida com outros trabalhos. O passo `ctrl-momo-opus` do `RenderHarness` agora espera
  o `dooble` entregue ao sistema de som (`Model sound dooble of wine_fox/14_momo handed to the sound system ... Ogg
  Opus, streamed`).

**A checar** (no jogo, com placa de som):

* Os quatro sons da Momo tocando de verdade num cliente 1.7.10 (no emote/animação que os chama), posicionais e
  seguindo o jogador, com volume e pitch como os Vorbis; o `fly_on` repetido (som guardado decodificado, liberado
  quando o modelo é liberado).
* Streaming longo: `owo` (174 s) até o fim e em laço (`ysm.play_sound` modo 4), sem engasgo; o custo de CPU da
  decodificação na thread de streaming do paulscode num cliente fraco.
* O passo `ctrl-momo-opus` do harness (`only ctrl`) e o relatório dos sons.
* Um modelo com Opus estéreo ou multicanal (família 1) de verdade (só as fixtures geradas foram testadas).
* Outro mod que também registre um codec `opus` no paulscode (o último registro vence).
