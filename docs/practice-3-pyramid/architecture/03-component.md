# А3. C4 Component: Authorization и Card Management

## Чего не хватало

Не было схемы внутренних компонентов Authorization и Card Management: контроллеров, бизнес-логики, HTTP-клиентов, доступа к базе данных и обработки outbox.

## Диаграмма

### Authorization

```mermaid
flowchart LR
    SW["Switch"]
    CMS["Card Management"]
    BIN["BIN Lookup"]
    DB[("PostgreSQL")]
    subgraph AUTH["Authorization"]
        AC["AuthControllerImpl"]
        AS["AuthServiceImpl"]
        CC["CardManagementClientImpl<br/>RestClient"]
        BC["BinLookupClient<br/>RestClient"]
        LR["LimitUsageRepository<br/>JpaRepository"]
        AC -->|"authorize(), rollback()"| AS
        AS -->|"getCard(), reserve(), rollback()"| CC
        AS -->|"getIssuerId()"| BC
        AS -->|"upsertLimitUsage(), fetchRrnBlock()"| LR
    end
    SW -->|"HTTP · sync<br/>/api/internal/authorize, /api/internal/rollback"| AC
    CC -->|"HTTP · sync<br/>/api/cards/*"| CMS
    BC -->|"HTTP · sync<br/>/api/bin/*"| BIN
    LR -->|"JPA / JDBC · sync<br/>limit_usage, rrn_seq"| DB
    classDef component fill:#dbeafe,stroke:#2563eb,color:#172554
    classDef storage fill:#dcfce7,stroke:#15803d,color:#14532d
    class AC,AS,CC,BC component
    class LR,DB storage
```

### Card Management

```mermaid
flowchart TB
    AUTH["Authorization"]
    DB[("PostgreSQL")]
    RMQ["RabbitMQ<br/>smp.card-events"]
    subgraph CMS["Card Management"]
        CTRL["CardController"]
        CS["CardServiceImpl"]
        BIS["BinIssuerServiceImpl"]
        TR["TransactionRunnerImpl"]
        CR["CardRepositoryPersistenceAdapter<br/>CardJpaRepository"]
        RR["ReservationRepositoryPersistenceAdapter<br/>ReservationJpaRepository"]
        RBR["ReservationRollbackRepositoryPersistenceAdapter<br/>ReservationRollbackJpaRepository"]
        BR["BinIssuerJpaAdapter<br/>BinIssuerJpaRepository"]
        NOTIFIER["CardEventNotifierImpl"]
        OP["OutboxProcessor<br/>@Scheduled · 1000 ms"]
        EP["OutboxEventProcessorImpl"]
        OR["OutboxJpaAdapter<br/>OutboxEventJpaRepository"]
        RT["RabbitTemplate"]

        CTRL -->|"CardService"| CS
        CS -->|"getIssuerId()"| BIS
        BIS -->|"BinIssuerRepository"| BR
        CS -->|"run(), runSupplier()"| TR
        CS -->|"CardRepository"| CR
        CS -->|"ReservationRepository"| RR
        CS -->|"ReservationRollbackRepository"| RBR
        CS -->|"notifyListeners()"| NOTIFIER
        NOTIFIER -->|"save() · CardOutboxEvent"| EP
        OP -->|"findPending()"| OR
        OP -->|"processSingleEvent()"| EP
        EP -->|"save()"| OR
        EP -->|"convertAndSend()"| RT
    end

    AUTH -->|"HTTP · sync<br/>/api/cards/*"| CTRL
    CR -->|"JPA / JDBC · sync<br/>cards"| DB
    RR -->|"JPA / JDBC · sync<br/>reservations"| DB
    RBR -->|"JPA / JDBC · sync<br/>reservation_rollbacks"| DB
    BR -->|"JPA / JDBC · sync<br/>bin_issuers"| DB
    OR -->|"JPA / JDBC · sync<br/>outbox_event"| DB
    RT -.->|"AMQP · async<br/>card.*"| RMQ
    classDef component fill:#dbeafe,stroke:#2563eb,color:#172554
    classDef storage fill:#dcfce7,stroke:#15803d,color:#14532d
    class CTRL,CS,BIS,TR,NOTIFIER,OP,EP,RT component
    class CR,RR,RBR,BR,OR,DB,RMQ storage
```

## Что я сделала

Я показала контроллеры, сервисы, HTTP-клиенты и репозитории Authorization и Card Management. Отдельно показала сохранение outbox и его публикацию через RabbitTemplate.

HTTP и JDBC отмечены как sync, AMQP отмечен как async. Внутренние вызовы подписаны методами и интерфейсами.

## Особенности реализации

Outbox повторно обрабатывается при следующем запуске. Пауза после завершения обработчика по умолчанию равна 1000 мс и не растёт. После трёх ошибок публикации событие получает `FAILED`.

## Подтверждение связей по коду

### Authorization

- **Контроллер → бизнес-логика Authorization:** [AuthControllerImpl.java](../../../services/authorization/src/main/java/com/processing/authorization/controller/AuthControllerImpl.java), [AuthServiceImpl.java](../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java).
- **HTTP-клиенты Authorization:** [CardManagementClientImpl.java](../../../services/authorization/src/main/java/com/processing/authorization/client/CardManagementClientImpl.java), [BinLookupClient.java](../../../services/authorization/src/main/java/com/processing/authorization/client/BinLookupClient.java).
- **Authorization → PostgreSQL:** [LimitUsageRepository.java](../../../services/authorization/src/main/java/com/processing/authorization/repositories/LimitUsageRepository.java).

### Card Management

- **Контроллер → бизнес-логика Card Management:** [CardController.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/controllers/CardController.java), [CardServiceImpl.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java).
- **Определение эмитента:** [BinIssuerServiceImpl.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/BinIssuerServiceImpl.java), [BinIssuerJpaAdapter.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/repositories/BinIssuerJpaAdapter.java).
- **Доступ к картам и резервированиям:** [CardRepositoryPersistenceAdapter.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/repositories/CardRepositoryPersistenceAdapter.java), [ReservationRepositoryPersistenceAdapter.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/repositories/ReservationRepositoryPersistenceAdapter.java), [ReservationRollbackRepositoryPersistenceAdapter.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/repositories/ReservationRollbackRepositoryPersistenceAdapter.java).
- **Транзакции и блокировки:** [TransactionRunnerImpl.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/TransactionRunnerImpl.java), [CardJpaRepository.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/repositories/CardJpaRepository.java).
- **Событие → outbox → RabbitMQ:** [CardEventNotifierImpl.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/events/CardEventNotifierImpl.java), [OutboxProcessor.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxProcessor.java), [OutboxEventProcessorImpl.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxEventProcessorImpl.java), [OutboxJpaAdapter.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/repositories/OutboxJpaAdapter.java).
- **Сборка компонентов и настройки:** [AppConfig.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/configuration/AppConfig.java), [RabbitMQConfig.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/config/RabbitMQConfig.java), [application.properties](../../../services/card-management/src/main/resources/application.properties).
