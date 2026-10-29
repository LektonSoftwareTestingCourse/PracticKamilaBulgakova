# А2. C4 Container

## Чего не хватало

В общей схеме сервисов не были разделены синхронные и асинхронные связи. Не были показаны outbox и настройки подтверждения публикации. Состав сервисов не был сверен с `docker-compose.yaml`.

## Диаграмма

```mermaid
flowchart TB
    USER(["Пользователь дашборда<br/>Браузер"])
        TERM["Terminal Simulator<br/>8085"]
        MERCH["Merchant Acquirer<br/>8084"]
        BIN["BIN Lookup<br/>8096"]
        WEB["Dashboard<br/>3000 · React / nginx"]
        GW["Gateway<br/>8080"]
        SW["Switch<br/>8082 · confirms: correlated"]
        AUTH["Authorization<br/>8083"]
        CMS["Card Management<br/>8081 · outbox<br/>confirms: correlated"]
        LOG["Transaction Logger<br/>8088"]
        NS["Notification Service<br/>8097"]
        DB[("PostgreSQL<br/>5432")]
        subgraph RMQ["RabbitMQ · AMQP 5672"]
            TXE["exchange: smp.transactions<br/>queue: transaction-log<br/>topic · key: transaction.log"]
            CE["exchange: smp.card-events<br/>queue: card-notifications<br/>topic · key: card.*"]
        end

    USER -->|"HTTP · sync"| WEB
    WEB -->|"HTTP / REST · sync<br/>/api/*"| GW
    LOG -.->|"WebSocket · async<br/>/ws/transactions"| WEB
    WEB -.->|"WebSocket · async"| USER
    GW --->|"HTTP · sync<br/>/api/simulator/terminal/*"| TERM
    TERM -->|"HTTP · sync<br/>/api/transactions"| GW
    TERM -->|"HTTP · sync<br/>/api/cards"| CMS
    GW --->|"HTTP · sync<br/>/api/simulator/merchant/*"| MERCH
    MERCH -->|"HTTP · sync<br/>/api/transactions, /api/cards"| GW
    GW -->|"HTTP · sync<br/>/api/internal/route"| SW
    GW -->|"HTTP · sync<br/>/api/cards/*"| CMS
    GW -->|"HTTP · sync<br/>/api/transactions/*<br/>/api/dashboard/*"| LOG
    SW -->|"HTTP · sync<br/>/api/internal/authorize<br/>/api/internal/rollback"| AUTH
    SW -->|"HTTP · sync<br/>/api/simulator/merchant/fee"| MERCH
    AUTH -->|"HTTP · sync<br/>/api/cards/*"| CMS
    AUTH -->|"HTTP · sync<br/>/api/bin/*"| BIN
    CMS -->|"JDBC · sync<br/>cards, reservations<br/>reservation_rollbacks<br/>bin_issuers, outbox_event"| DB
    AUTH -->|"JDBC · sync<br/>limit_usage, rrn_seq"| DB
    LOG -->|"JDBC · sync<br/>transactions"| DB
    NS -->|"JDBC · sync<br/>card_notifications"| DB
    MERCH -->|"JDBC · sync<br/>merchants, terminals<br/>acquirer_fee"| DB
    SW -.->|"AMQP · async<br/>transaction.log"| TXE
    TXE -.->|"AMQP · async<br/>transaction-log"| LOG
    CMS -.->|"AMQP · async<br/>outbox · card.*"| CE
    CE -.->|"AMQP · async<br/>card-notifications"| NS
    classDef service fill:#dbeafe,stroke:#2563eb,color:#172554
    classDef external fill:#f1f5f9,stroke:#64748b,color:#202020
    classDef storage fill:#dcfce7,stroke:#15803d,color:#14532d
    class WEB,GW,SW,AUTH,CMS,LOG,NS service
    class TERM,MERCH,BIN,USER external
    class DB,TXE,CE storage
```

## Что я сделала

Я сверила состав с Compose, порты с `.env.example`. Показала HTTP/JDBC, AMQP, WebSocket, outbox, exchanges и очереди. Сплошные стрелки обозначают sync, пунктирные обозначают async.

На схеме указаны порты доступа с компьютера. В сети Docker HTTP-сервисы используют `8080`, Dashboard использует `3000`.

## Особенности реализации

- В базовом Compose 10 прикладных сервисов. Отдельного одиннадцатого сервиса нет.
- К PostgreSQL подключаются Card Management, Authorization, Logger, Notification и Merchant Acquirer.
- У Switch и Card Management включён режим confirms `correlated`, но отправители не ждут подтверждения. Switch запускает откат при ошибке отправки одобренной транзакции.

## Подтверждение связей по коду

- **Состав и порты:** [docker-compose.yaml](../../../docker-compose.yaml), [.env.example](../../../.env.example).
- **Dashboard → Gateway, Logger → Dashboard:** [nginx.conf](../../../services/dashboard/nginx.conf), [useWebSocket.ts](../../../services/dashboard/src/hooks/useWebSocket.ts), [TransactionService.java](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/service/TransactionService.java): HTTP и WebSocket.
- **Терминал → Gateway и Card Management:** [GatewayClient.java](../../../services/terminal-simulator/src/main/java/com/processing/terminalsimulator/client/GatewayClient.java).
- **Мерчант → Gateway:** [GatewayClient.java](../../../services/merchant-acquirer/src/main/java/com/processing/merchantacquirer/client/GatewayClient.java).
- **Gateway → сервисы:** [application.yml](../../../services/gateway/src/main/resources/application.yml): маршруты и адреса.
- **Switch → Authorization и Merchant Acquirer:** [AuthorizationClient.java](../../../services/switch/src/main/java/com/processing/service/AuthorizationClient.java), [MerchantAcquirerClient.java](../../../services/switch/src/main/java/com/processing/service/MerchantAcquirerClient.java).
- **Authorization → Card Management, BIN Lookup и PostgreSQL:** [CardManagementClientImpl.java](../../../services/authorization/src/main/java/com/processing/authorization/client/CardManagementClientImpl.java), [BinLookupClient.java](../../../services/authorization/src/main/java/com/processing/authorization/client/BinLookupClient.java), [LimitUsageRepository.java](../../../services/authorization/src/main/java/com/processing/authorization/repositories/LimitUsageRepository.java).
- **Card Management → PostgreSQL:** [CardJpaRepository.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/repositories/CardJpaRepository.java), [ReservationJpaRepository.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/repositories/ReservationJpaRepository.java), [ReservationRollbackJpaRepository.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/repositories/ReservationRollbackJpaRepository.java), [BinIssuerJpaRepository.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/repositories/BinIssuerJpaRepository.java), [OutboxEventJpaRepository.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/repositories/OutboxEventJpaRepository.java).
- **Logger и Notification → PostgreSQL:** [TransactionRepository.java](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/repository/TransactionRepository.java), [CardNotificationRepository.java](../../../services/notification-service/src/main/java/com/processing/notification/repository/CardNotificationRepository.java).
- **Merchant Acquirer → PostgreSQL:** [Merchant.java](../../../services/merchant-acquirer/src/main/java/com/processing/merchantacquirer/domain/entity/Merchant.java), [Terminal.java](../../../services/merchant-acquirer/src/main/java/com/processing/merchantacquirer/domain/entity/Terminal.java), [AcquirerFee.java](../../../services/merchant-acquirer/src/main/java/com/processing/merchantacquirer/domain/entity/AcquirerFee.java), [docker-compose.yaml](../../../docker-compose.yaml).
- **Switch → RabbitMQ → Logger:** [LoggerClient.java](../../../services/switch/src/main/java/com/processing/service/LoggerClient.java), [RabbitMQConfig.java](../../../services/switch/src/main/java/com/processing/config/RabbitMQConfig.java), [TransactionLogListener.java](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/listener/TransactionLogListener.java).
- **Outbox → RabbitMQ → Notification:** [OutboxEventProcessorImpl.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxEventProcessorImpl.java), [RabbitMQConfig.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/config/RabbitMQConfig.java), [CardEventListener.java](../../../services/notification-service/src/main/java/com/processing/notification/listener/CardEventListener.java).
- **Publisher confirms и откат:** [application.yml](../../../services/switch/src/main/resources/application.yml), [application.properties](../../../services/card-management/src/main/resources/application.properties), [RouteService.java](../../../services/switch/src/main/java/com/processing/service/RouteService.java).
