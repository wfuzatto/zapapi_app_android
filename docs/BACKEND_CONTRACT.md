# Contrato da API móvel — WhatsApp Gateway V1

**O app Android não fala com a Graph API da Meta.** Ele fala com um adaptador HTTPS próprio, autenticado e restrito ao tenant.

Base URL:
```
https://zapapi.inf.br/mobile-api/v1/
```

> O contrato é implementado no repositório `wfuzatto/whatsapp_gateway`, em `/mobile-api/v1/`. Não utiliza a V2. Alguns recursos avançados (mídia e notificações push ativas) permanecem planejados.

## Autenticação

`POST auth/login` JSON `{"email":"operador@empresa.com","password":"..."}` -> 200 `{"access_token":"TOKEN_OPACO_COM_20_OU_MAIS_CARACTERES","expires_in":3600}`

- HTTPS obrigatório, senha validada por hash do servidor.
- Restringir usuários ativos e tenant ativo, controlar tentativas, emitir sessão revogável de curta duração.
- Não utilizar token da Meta, App Secret, cookie PHP ou token de API de integração externa como sessão mobile.
- Recomendação futura: refresh tokens rotativos vinculados ao dispositivo.
- Todas as rotas seguintes exigem `Authorization: Bearer <access_token>`.
- `POST auth/logout` -> 200 `{"ok":true}` (revoga sessão).

## Conversas

`GET conversations` ->

```json
{"data":[{"id":"123","name":"Prodatas","phone":"5535988566222","last_message":"Oi","updated_at":"09:31","unread_count":1}]}
```

Somente dados associados ao tenant da sessão. Não aceitar `tenant_id` do aplicativo.

`POST conversations` JSON `{"phone":"5535988566222"}` -> 200 `{"id":"123"}`

Cria ou abre uma conversa existente para o telefone dentro do tenant; **não envia mensagem automaticamente**. Validar número E.164 sem +.

`GET conversations/{id}/messages` ->

```json
{"data":[
 {"id":"1","text":"Oi","direction":"inbound","created_at":"09:31","status":"received"},
 {"id":"2","text":"Olá!","direction":"outbound","created_at":"09:32","status":"read"}
]}
```

Ordenar cronologicamente. Excluir campos sensíveis e não expor payloads brutos do webhook.

`POST conversations/{id}/messages` JSON `{"text":"Olá!"}` -> 200/202 `{"id":"3","status":"queued"}`

- **Backend** valida consentimento e janela real de atendimento de 24 horas. Mensagens de teste jamais abrem janela.
- Para conversas fora da janela, responder **409** com `{"error":"É necessário usar um template aprovado."}`, sem enviar texto livre.
- Conectar ao mecanismo de envio da V1, não duplicar implementação da Cloud API.
- Usar fila/idempotência e persistir status real obtido por webhooks da Meta.
- Validar permissões do operador na conversa.

## Firebase (preparado)

`POST devices` JSON `{"platform":"android","fcm_token":"..."}` -> 200 `{"ok":true}`

- Autenticação obrigatória. Associar o FCM token à sessão, usuário, tenant e dispositivo.
- Substituir token em `onNewToken`, revogar ao deslogar.
- Enviar push pelo **Firebase Admin SDK/FCM HTTP v1 no backend**, nunca do APK.
- Não enviar mensagens pessoais no corpo do push; apenas `{"type":"new_message","conversation_id":"123"}` para disparar sincronização.
- Proteção contra recebimento de notificações de outros tenants e desduplicação de eventos.

## Testes de aceitação

1. Login correto e inválido, bloqueio por excesso de tentativas, expiração e revogação.
2. Duas empresas distintas não veem as conversas uma da outra.
3. Listagem recebe o contato real criado pelo webhook Meta.
4. Envio dentro da janela aparece em WhatsApp e chega a `read`.
5. Envio fora da janela devolve 409.
6. Instalação sem `google-services.json` não exige Firebase; quando habilitado, token é registrado após login.
7. Push só é entregue ao tenant correto, e logout remove autorização.
8. API responde com JSON também para erros; não redireciona para login HTML.

## Integrações planejadas

- Listagem e envio de templates aprovados ao iniciar conversas fora de 24 horas.
- Upload/preview seguro de mídias.
- Respostas rápidas, arquivos, fotos, áudios, atribuição de atendentes e presença.
- Cursor incremental ou WebSocket/SSE para reduzir polling de 6/12 segundos.

O desenho visual foi criado para parecer familiar a mensageiros, mas o app é **Ponte**, não o cliente oficial do WhatsApp.
