# Ponte Mobile — ZapAPI Android

Aplicativo Android nativo (Kotlin + Jetpack Compose) para acompanhar as conversas da plataforma Ponte / WhatsApp Gateway **V1**, sem utilizar WhatsApp Web nem inserir credenciais da Meta no celular.

## Estado

- Interface estilo mensageiro: conversas, busca, chat, campos de envio e indicadores de mensagem.
- Login próprio do backend; token de sessão armazenado com Android Keystore AES-GCM.
- Cliente HTTP para `/mobile-api/v1/`; atualização periódica enquanto o app está em primeiro plano.
- Firebase Cloud Messaging preparado: serviço de mensagens e sincronização do token FCM quando `google-services.json` for configurado.
- **A API `/mobile-api/v1/` é um contrato novo; precisa ser implementada na V1.** O fato de `/webhook` e da central web funcionarem não significa que a API móvel já exista.
- Não contém token Meta, App Secret ou credenciais privadas.

## Requisitos

Android Studio atualizado, Android SDK 35+, JDK 17, conexão HTTPS.

Abra a pasta no Android Studio, sincronize Gradle e execute `app`. O projeto usa AGP 8.9.2, Kotlin 2.1.20 e Compose BOM 2025.04.01, versões conservadoras para facilitar o primeiro build. Uma atualização de dependências pode ser feita após a primeira compilação.

Para habilitar Firebase: crie o app Android no Firebase com package `br.com.prodatastelecom.zapapi`, coloque o `google-services.json` em `app/`, sincronize o Gradle. A configuração é opcional para builds sem Firebase.

Veja `docs/BACKEND_CONTRACT.md` para os endpoints necessários e política de segurança.

## Próxima etapa

Implementar o adaptador `/mobile-api/v1/` no servidor da V1 com autenticação de usuário, isolamento tenant e políticas de 24 horas / templates da Meta. Não reutilizar cookies web nem entregar tokens da Cloud API ao dispositivo.


## Android v1.0.0 — ZapApi Messenger

O APK de teste é compilado pelo GitHub Actions após cada push e aparece em **Actions → Android — compilação debug → Artifacts → zapapi-messenger-debug**. A V2 não foi modificada. O endpoint inicial é `https://zapapi.inf.br/mobile-api/v1/`, configurável pela engrenagem no login; alterar o servidor invalida a sessão local. O backend V1 possui login de tenant, conversas, contatos e templates aprovados. Configurar o FCM é opcional para compilar; o backend precisa de infraestrutura própria para enviar pushes.

O APK gerado no CI é **debug assinado com chave de desenvolvimento**, adequado para instalação interna/teste, não para Play Store. 
