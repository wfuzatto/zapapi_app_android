# Contrato da API móvel — ZapApi

**O app Android não fala com a Graph API da Meta.** Ele fala com um adaptador HTTPS próprio, autenticado e restrito ao tenant.

URL base da plataforma e prefixo:
```
https://zapapi.inf.br
mobile-api/v1
```
O endpoint completo fica em `https://zapapi.inf.br/mobile-api/v1/`. O app também aceita que a URL base termine com `/mobile-api/v1`; ele normaliza o endereço para não repetir o prefixo.

> O contrato é servido pelo domínio oficial da plataforma em `/mobile-api/v1/`. O app não chama o domínio legado. A leitura de mídia é restrita à sessão e ao tenant; envio de mídia pelo app e notificações push ativas continuam planejados.

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
 {"id":"1","text":"Oi","direction":"inbound","created_at":"09:31","status":"received","type":"text","has_media":false,"media_mime":"","media_filename":""},
 {"id":"2","text":"Anexo recebido: audio","direction":"inbound","created_at":"09:32","status":"received","type":"audio","has_media":true,"media_mime":"audio/ogg","media_filename":""}
]}
```

Ordenar cronologicamente. Excluir campos sensíveis e não expor payloads brutos do webhook.
O endpoint não expõe o ID interno da Meta nem URLs externas. `GET media/{message_id}` retorna os bytes da mídia pela sessão móvel, após confirmar que a mensagem pertence ao tenant. Imagens, stickers, áudio e vídeo são servidos inline; documentos são servidos como anexo.

`GET media/{message_id}` -> bytes do arquivo

- Exige `Authorization: Bearer <access_token>`.
- A consulta liga a mensagem ao tenant da sessão e só aceita mensagens com anexo.
- O backend usa o proxy de mídia da dashboard, que valida o host da Meta, limita o tamanho e não expõe o token da Meta ao app.

`POST conversations/{id}/messages` JSON `{"text":"Olá!"}` -> 200/202 `{"id":"3","status":"queued"}`

- **Backend** valida consentimento e janela real de atendimento de 24 horas. Mensagens de teste jamais abrem janela.
- Para conversas fora da janela, responder **409** com `{"error":"É necessário usar um template aprovado."}`, sem enviar texto livre.
- Conectar ao mecanismo de envio do ZapApi, sem duplicar a implementação da Cloud API.
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
9. Imagens, stickers, áudios, vídeos e documentos só podem ser baixados por sessão válida do tenant proprietário.

## Integrações planejadas

- Listagem e envio de templates aprovados ao iniciar conversas fora de 24 horas.
- Envio de mídias pelo app.
- Respostas rápidas, arquivos, fotos, áudios, atribuição de atendentes e presença.
- Push ativo via Firebase para reduzir o polling de mensagens.

O desenho visual foi criado para parecer familiar a mensageiros, mas o app é **Ponte**, não o cliente oficial do WhatsApp.

## Cache local e sincronizacao incremental

- `GET conversations/{id}/messages?limit=50` retorna no maximo as 50 mensagens mais recentes, em ordem cronologica, com `has_more`, `oldest_id` e `newest_id`.
- `before_id` busca a proxima pagina antiga, limitada a 50 mensagens. O APK so persiste essa pagina depois que ela e carregada ao chegar ao topo da lista.
- `after_id` sincroniza mensagens novas. `ids` pede somente a atualizacao de status das mensagens que ja estao carregadas e das mensagens pendentes de confirmacao.
- O APK usa SQLite privado, separado pela sessao ativa, e limpa o cache ao sair, trocar de conta ou trocar de servidor.
