<div align="center">

# CHIRON

### Monitoramento inteligente de conteúdo para crianças

*Um guardião digital que observa a tela, entende o que está sendo exibido e protege a criança em tempo real.*

</div>

---

## O que é o Chiron

O Chiron é uma aplicação mobile Android com duas funções principais que trabalham juntas para proteger crianças de conteúdo inadequado.

A primeira função é o **monitoramento de conteúdo**: o app captura prints da tela de forma recorrente e os encaminha para o Gemini através de uma API que se conecta ao modelo generativo no servidor remoto do Google. O Gemini avalia se cada print contém conteúdo inadequado, com base em regras de conteúdo definidas pelos responsáveis (por exemplo: nada de violência, luta, conteúdo adulto ou assustador).

A segunda função entra em ação quando o conteúdo é considerado inadequado: a **overlay**. A overlay é uma janela que aparece na frente do aplicativo de conteúdo, como o YouTube, e bloqueia a tela com um filtro. Para remover a overlay, o jovem precisa sair do conteúdo impróprio.

No estado atual do MVP, já conseguimos capturar e enviar o print, e já conseguimos agir de acordo com o veredito do Gemini, exibindo ou removendo a overlay. A etapa em que o app detecta automaticamente que o jovem saiu do conteúdo ainda não foi implementada e faz parte dos próximos passos.

---

## Como o Chiron funciona, por baixo dos panos

```
   App da criança (ex: YouTube)
              |
              v
   [1] Captura recorrente da tela (MediaProjection)
              |
              v
   [2] Compressão e otimização da imagem (resize + JPEG)
              |
              v
   [3] Envio para o Gemini + regras dos responsáveis
              |
              v
   [4] Veredito: ADEQUADO ou INADEQUADO
              |
       +------+------+
       |             |
   ADEQUADO      INADEQUADO
       |             |
   nada a        [5] Overlay cobre
   fazer             a tela na hora
```

---

## Organização do projeto

O projeto segue a estrutura padrão de um app Android moderno em Kotlin com Jetpack Compose:

```
ChironProject/
├── app/
│   ├── google-services.json          # configuração do Firebase (conexão com o Gemini)
│   ├── build.gradle.kts              # dependências do módulo do app
│   └── src/main/
│       ├── AndroidManifest.xml       # permissões (overlay, captura, internet) e serviços
│       ├── java/com/Luis/chironproject/
│       │   ├── MainActivity.kt       # tela inicial + fluxo de permissões
│       │   ├── services/
│       │   │   └── OverlayService.kt # núcleo: captura + análise (Gemini) + overlay
│       │   ├── ui/
│       │   │   ├── screens/
│       │   │   │   └── OverlayScreen.kt   # tela de bloqueio exibida sobre o conteúdo
│       │   │   └── theme/
│       │   │       ├── Color.kt      # paleta da identidade visual Chiron
│       │   │       ├── Theme.kt      # tema do app (cores aplicadas)
│       │   │       └── Type.kt       # tipografia (fonte Bauhaus nos títulos)
│       │   └── utils/
│       │       └── Constants.kt      # intervalo de verificação e constantes
│       └── res/
│           ├── drawable/             # logos do Chiron
│           └── font/                 # fonte Bauhaus
├── build.gradle.kts                  # configuração de build do projeto (plugins)
└── gradle/
    └── libs.versions.toml            # catálogo de versões de dependências
```

### Os três arquivos centrais

**`OverlayService.kt`** é o coração do app. Ele roda em segundo plano como um *foreground service*, captura a tela periodicamente, comprime a imagem, envia para o Gemini junto com as regras de conteúdo, interpreta o veredito e decide se exibe ou esconde a overlay.

**`OverlayScreen.kt`** é a interface da tela de bloqueio — o que a criança vê quando um conteúdo é barrado. Foi construída com a identidade visual do Chiron (fundo roxo, logo e textos em laranja).

**`MainActivity.kt`** é a porta de entrada. Apresenta o app e conduz o fluxo de permissões necessárias (exibição sobre outros apps e captura de tela) antes de iniciar a proteção.

---

## A jornada para rodar o projeto

> **Aviso honesto:** este projeto depende de credenciais privadas do Firebase e de uma chave de acesso ao Gemini que são específicas da nossa conta. Por isso, **não é possível simplesmente clonar e rodar** — seria necessário recriar toda a infraestrutura de back-end com as próprias credenciais. O passo a passo abaixo documenta a jornada que percorremos, tanto para registro quanto para quem quiser reconstruir o ambiente do zero.

### 1. Acesso ao modelo generativo (Google AI Studio)

O ponto de partida foi o **Google AI Studio**, onde geramos a primeira chave de acesso ao Gemini. Aqui encontramos o primeiro desafio: o Google está em transição no formato das chaves de API, e as chaves novas saem em um formato (`AQ.`) que o SDK antigo de acesso direto ao Gemini não aceitava mais.

### 2. Migração para o Firebase AI Logic

Por causa dessa incompatibilidade, migramos a conexão para o **Firebase AI Logic**, que é o caminho oficial e atual para acessar o Gemini a partir de um app Android. Isso envolveu:

- Criar um projeto no Firebase Console
- Registrar o app Android com o nome de pacote correto
- Baixar o arquivo `google-services.json` e colocá-lo na pasta `app/`
- Ativar o serviço de IA (AI Logic) dentro do projeto Firebase
- Adicionar o plugin do Google Services e as dependências do Firebase no Gradle

A grande vantagem dessa abordagem é que a chave de API deixa de ficar exposta no código — a autenticação passa a ser gerenciada pelo Firebase.

### 3. SDK e ambiente de desenvolvimento

O ambiente foi montado com:

- **Android Studio** como IDE
- **Android SDK** (instalado pelo próprio Android Studio)
- **JDK 17+** (usamos o Java 21, totalmente compatível)
- **Gradle** como sistema de build, responsável por baixar dependências e gerar o APK

### 4. Build com o Gradle

Com o ambiente configurado, o build é feito pelo Gradle (integrado ao Android Studio). Após o `Sync` das dependências e um `Make Project`, o Android Studio gera o APK e instala no dispositivo selecionado.

### 5. Onde testar: o melhor "emulador"

Durante o desenvolvimento usamos tanto o **emulador do Android Studio (Pixel 6, API 34)** quanto um **celular físico**. A recomendação honesta:

- Para validar a interface, o fluxo de permissões e a conexão com o Gemini, o **emulador Pixel 6 com API 34** funciona bem.
- Para a experiência completa e fluida — especialmente a captura de tela sobre apps reais como o YouTube — o **celular físico** entrega o melhor resultado, pois a captura é mais natural e o desempenho é superior.

---

## Sobre a conexão com a API — o maior desafio técnico

Conectar a aplicação ao modelo generativo foi, de longe, a parte que mais deu trabalho neste MVP. Os principais obstáculos enfrentados:

- **Formato das chaves de API:** a transição do Google para o novo formato de chaves quebrou a compatibilidade com o SDK antigo e nos obrigou a migrar para o Firebase AI Logic.
- **Ciclo de vida dos modelos:** modelos do Gemini são descontinuados com certa frequência, então foi necessário garantir o uso de um modelo ativo e atual.
- **Limites do plano gratuito:** o free tier tem cota limitada de requisições por minuto e por dia, o que exigiu cuidado durante os testes para não esgotar o acesso.
- **Tamanho dos dados enviados:** prints de tela em resolução cheia são grandes demais para a API, o que nos levou a implementar compressão (redução de resolução + conversão para JPEG) antes do envio.

### O Gemini 2.5 é uma escolha de MVP, não definitiva

É importante deixar claro: **o uso do Gemini 2.5 (Flash / Flash-Lite) é uma solução voltada para o MVP**. Ela nos permitiu validar a ideia rapidamente e provar que o conceito funciona de ponta a ponta. Não é, porém, a configuração ideal para um produto em escala.

---

## Próximos passos

Para evoluir o Chiron de um MVP para um produto real e escalável, os próximos passos são:

1. **Encontrar o melhor modelo generativo** para a tarefa — avaliar custo, velocidade e precisão de diferentes modelos de visão, em vez de assumir que o Gemini 2.5 é a escolha final.
2. **Otimizar a compressão dos dados** para gastar o mínimo possível de tokens por análise. Como a aplicação vai rodar em escala, com muitas análises por usuário ao longo do dia, cada token economizado tem impacto direto no custo de operação.
3. **Implementar a detecção de saída do conteúdo** — fazer o app reconhecer automaticamente quando o jovem deixou o conteúdo impróprio para então remover a overlay.
4. **Refinar as regras de conteúdo** para que os responsáveis possam personalizar com precisão o que é ou não permitido, por faixa etária.

---

## Stack utilizada

| Categoria          | Tecnologia                          |
| ------------------ | ----------------------------------- |
| Linguagem          | Kotlin                              |
| Framework de UI    | Jetpack Compose                     |
| IDE                | Android Studio                      |
| Sistema de build   | Gradle (Kotlin DSL)                 |
| Captura de tela    | MediaProjection (Android SDK)       |
| Análise de imagem  | Gemini via Firebase AI Logic        |
| Overlay            | WindowManager + TYPE_APPLICATION_OVERLAY |

---

<div align="center">

**Projeto Chiron** — desenvolvido como MVP.

</div>
