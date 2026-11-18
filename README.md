# Ponte Mobile — ZapAPI Android

Aplicativo Android nativo (Kotlin + Jetpack Compose) para acompanhar as conversas da plataforma ZapApi, sem utilizar WhatsApp Web nem inserir credenciais da Meta no celular.

## Estado

- Interface estilo mensageiro: conversas, busca, chat, campos de envio e indicadores de mensagem.
- Login próprio do backend; token de sessão armazenado com Android Keystore AES-GCM.
- Cliente HTTP para `/mobile-api/v1/`; atualização periódica enquanto o app está em primeiro plano.
- Firebase Cloud Messaging preparado: serviço de mensagens e sincronização do token FCM quando `google-services.json` for configurado.
- A API móvel é servida pelo domínio oficial `zapapi.inf.br`. O app usa autenticação móvel própria e não reutiliza cookies do painel.
- A aba Contatos usa conversas já existentes; o app não inclui uma interface móvel para enviar templates.
- O chat carrega imagens, stickers, áudios, vídeos e documentos por uma rota móvel autenticada; os arquivos ficam no cache privado do aplicativo.
- As telas respeitam as barras de status/navegação e o compositor do chat acompanha dinamicamente a altura do teclado.
- Não contém token Meta, App Secret ou credenciais privadas.

## Requisitos

Android Studio atualizado, Android SDK 35+, JDK 17, conexão HTTPS.

Abra a pasta no Android Studio, sincronize Gradle e execute `app`. O projeto usa AGP 8.9.2, Kotlin 2.1.20 e Compose BOM 2025.04.01, versões conservadoras para facilitar o primeiro build. Uma atualização de dependências pode ser feita após a primeira compilação.

Para habilitar Firebase: crie o app Android no Firebase com package `br.com.prodatastelecom.zapapi`, coloque o `google-services.json` em `app/`, sincronize o Gradle. A configuração é opcional para builds sem Firebase.

Veja `docs/BACKEND_CONTRACT.md` para os endpoints necessários e política de segurança.

## Backend oficial ZapApi

O endereço padrão é `https://zapapi.inf.br` com o prefixo `mobile-api/v1`. Nas configurações, também é possível informar a URL completa terminada em `/mobile-api/v1`; o app remove esse sufixo antes de montar as rotas. Ao atualizar uma instalação existente, o app migra para o domínio oficial, encerra a sessão anterior e limpa o cache local.


## Android v1.1.0 — ZapApi Messenger

O APK de teste é compilado pelo GitHub Actions e pode ser instalado internamente. O endpoint inicial é `https://zapapi.inf.br/mobile-api/v1/`, configurável pela engrenagem no login; alterar o servidor invalida a sessão local. Configurar o FCM é opcional para compilar; o backend precisa de infraestrutura própria para enviar pushes.

O APK gerado no CI é **debug assinado com chave de desenvolvimento**, adequado para instalação interna/teste, não para Play Store. 
