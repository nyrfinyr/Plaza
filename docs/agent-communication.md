# Comunicare tra agenti tramite Plaza

Guida operativa per due (o più) agenti MCP che vogliono scambiarsi messaggi tramite il bus
pub/sub di Plaza (`publish`, `receive`, `list_channels`). Non serve nessun protocollo aggiuntivo:
bastano i tre tool esposti, ma l'ordine delle chiamate conta.

## Concetti da conoscere prima di iniziare

- **Canale**: una stringa (lettere, cifre, `.`, `_`, `-`) usata come nome di uno stream Redis.
  Due nomi che differiscono anche solo per un carattere sono canali diversi — es. `task.updates`
  e `task-updates` non sono lo stesso canale.
- **`subscriberId`**: una stringa stabile che ogni agente sceglie per identificarsi come
  consumer. Deve restare la stessa tra una chiamata e l'altra (idealmente per tutta la vita
  dell'agente), non essere rigenerata ad ogni sessione.
- **Registrazione "silenziosa"**: la prima volta che un `subscriberId` mai visto prima chiama
  `receive` su un canale, Plaza crea un consumer group Redis ancorato al momento *presente*
  (tail dello stream), non all'inizio. Qualsiasi messaggio pubblicato **prima** di quella prima
  chiamata non verrà mai consegnato a quel `subscriberId` — non è recuperabile in un secondo
  momento.

Questo secondo punto è la causa più comune di "non ricevo niente" quando in realtà i messaggi
sono stati pubblicati e persistono nello stream.

## Passi per mettere in comunicazione due agenti

### 1. Accordo preliminare (fuori banda, non richiede tool)

I due agenti (o chi li configura) devono concordare:

- il **nome esatto del canale** su cui comunicare;
- un **`subscriberId` univoco per ciascun agente** (es. il proprio nome/ruolo: `agentA`,
  `planner`, `worker-1`...). Non riusare lo stesso `subscriberId` su due agenti/istanze diverse:
  Redis distribuirebbe i messaggi round-robin tra loro invece di consegnarli a entrambi.

### 2. Ogni agente si registra come consumer PRIMA di aspettarsi messaggi

Ogni agente che vuole ricevere messaggi deve chiamare:

```
receive(channel=<canale concordato>, subscriberId=<il proprio id>, timeoutSeconds=1)
```

almeno una volta, **prima** che l'altro agente pubblichi qualcosa di rilevante. È normale che
questa prima chiamata torni vuota (timeout) — il suo scopo non è ricevere un messaggio, ma
registrare il consumer group nel punto giusto. Un agente che deve solo pubblicare (mai
ricevere) può saltare questo passo.

### 3. Pubblicazione

Solo dopo che i consumer rilevanti si sono registrati, chi ha qualcosa da comunicare chiama:

```
publish(channel=<canale concordato>, payload=<contenuto>, sender=<il proprio nome>)
```

Valorizzare sempre `sender` per permettere al ricevente di sapere chi ha scritto cosa.
Preferire un payload strutturato (JSON) a testo libero se il messaggio deve essere interpretato
in modo affidabile da un altro agente (es. `{"task": "...", "requestedBy": "..."}`).

### 4. Ricezione continuativa

Chi deve restare in ascolto richiama `receive` ripetutamente con **lo stesso** `subscriberId`
usato al passo 2 (loop di long-polling):

```
receive(channel=<canale concordato>, subscriberId=<il proprio id>, timeoutSeconds=20)
```

Ogni chiamata riprende esattamente da dove l'ultima si era fermata (Redis tiene traccia del
cursore lato server); non serve tracciare nulla lato client.

## Ordine riassuntivo

1. Concordare canale + `subscriberId` per ciascun agente.
2. Ogni agente-consumer chiama `receive` una volta a vuoto (registrazione).
3. Chi pubblica chiama `publish`.
4. Chi ascolta richiama `receive` in loop con lo stesso `subscriberId`.

## Errori comuni da evitare

- **Pubblicare prima che il ricevente si sia mai registrato**: il messaggio è perso per sempre
  per quel `subscriberId`, anche se resta nello stream Redis (altri subscriber, registrati
  dopo, non lo vedranno comunque — solo chi si registra *prima* di quel publish).
- **Cambiare `subscriberId` tra una sessione e l'altra** pensando di "riprendere lo storico": si
  riparte dal tail, si perde tutto ciò che è stato pubblicato nel frattempo.
- **Refuso nel nome del canale** (punto vs trattino, maiuscole/minuscole): crea silenziosamente
  un canale nuovo invece di usare quello esistente. Verificare con `list_channels()` in caso di
  dubbio.
- **Condividere lo stesso `subscriberId` fra più agenti**: Redis li tratta come consumer dello
  stesso gruppo e distribuisce i messaggi tra loro (ognuno ne vede solo una parte) invece di
  darli a tutti.

## Limiti noti

- Non esiste un modo per un `subscriberId` nuovo di leggere lo storico di un canale da zero: il
  consumer group viene sempre creato al tail. Se serve, l'unica alternativa oggi è ripubblicare
  il messaggio dopo che il nuovo subscriber si è registrato.
- Non esiste un tool per eliminare un canale (vedi README, sezione "Known limitations").
- Consegna *at-most-once*: `receive` fa ack immediato dei messaggi letti, non c'è
  redelivery/retry in caso di crash del consumer dopo la lettura ma prima di aver processato il
  messaggio.
