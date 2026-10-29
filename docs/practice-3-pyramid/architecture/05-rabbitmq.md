# А5. RabbitMQ: exchanges, очереди и потребители

## Чего не хватало

Связи RabbitMQ были описаны текстом и таблицей. Не было отдельной схемы с exchanges, очередями, потребителями и очередями для сообщений, которые не удалось обработать.

## Диаграмма

```mermaid
flowchart LR
    SW["Switch<br/>LoggerClient"]
    OUTBOX["Card Management<br/>OutboxProcessor / OutboxEventProcessorImpl<br/>interval = 1000 ms · maxRetryCount = 3<br/>При 3 ошибках публикации: FAILED"]
    LOG["Transaction Logger<br/>TransactionLogListener<br/>AMQP · async"]
    NS["Notification Service<br/>CardEventListener<br/>AMQP · async"]

    subgraph RMQ["RabbitMQ"]
        subgraph TX["Транзакции"]
            TE["smp.transactions<br/>topic · durable"]
            TQ["transaction-log<br/>durable · x-max-delivery = 3<br/>3 попытки у потребителя не обеспечены"]
            TD["smp.transactions.dlx<br/>direct · durable"]
            TDQ["transaction-log-dlq<br/>durable · TTL = 60 s"]
            TE -.->|"transaction.log"| TQ
            TQ -.-> TD
            TD -.->|"transaction-log"| TDQ
        end
        subgraph CARD["Карточные события"]
            CE["smp.card-events<br/>topic · durable"]
            CQ["card-notifications<br/>durable · x-max-delivery = 3<br/>3 попытки у потребителя не обеспечены"]
            CD["smp.card-events.dlx<br/>direct · durable"]
            CDQ["card-notifications-dlq<br/>durable · TTL = 60 s"]
            CE -.->|"card.*"| CQ
            CQ -.-> CD
            CD -.->|"card-notifications"| CDQ
        end
    end

    SW -.->|"AMQP · async<br/>transaction.log<br/>publisher-confirm-type = correlated<br/>Ожидание confirm 2 s не реализовано"| TE
    OUTBOX -.->|"AMQP · async<br/>card. + имя класса события<br/>publisher-confirm-type = correlated<br/>Подтверждение не ожидается"| CE
    TQ -.-> LOG
    CQ -.-> NS

    classDef service fill:#dbeafe,stroke:#2563eb,color:#172554
    classDef queue fill:#dcfce7,stroke:#15803d,color:#14532d
    classDef dead fill:#fee2e2,stroke:#b91c1c,color:#7f1d1d
    class SW,OUTBOX,LOG,NS service
    class TE,TQ,CE,CQ queue
    class TD,TDQ,CD,CDQ dead
```

## Что я сделала

Я показала оба пути сообщений, routing keys, потребителей, DLX и DLQ. Разделила повторы публикации outbox и повторы обработки потребителем. TTL 60 секунд задан у DLQ.

## Особенности реализации

- Отправители не ждут publisher-confirm. Успех отправки и статус outbox `PROCESSED` ещё не подтверждают доставку потребителю.
- Outbox повторяет публикацию после ошибки при следующем запуске обработчика. После трёх ошибок событие получает `FAILED`. Пауза по умолчанию равна 1000 мс после завершения обработчика, без увеличения.
- У основных очередей задан `x-max-delivery=3`, но retry потребителей не настроен. Доставка ровно три раза и последующий переход в DLQ не обеспечены. Стрелки к DLX показывают настроенные dead-letter-связи.

## Подтверждение связей по коду

- **Switch → exchange транзакций:** [LoggerClient.java](../../../services/switch/src/main/java/com/processing/service/LoggerClient.java), [RabbitMQConfig.java](../../../services/switch/src/main/java/com/processing/config/RabbitMQConfig.java): публикация, exchange, основная очередь, DLX и DLQ.
- **Очередь транзакций → Logger:** [RabbitMQConfig.java](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/config/RabbitMQConfig.java), [TransactionLogListener.java](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/listener/TransactionLogListener.java).
- **Outbox → exchange карточных событий:** [OutboxProcessor.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxProcessor.java), [OutboxEventProcessorImpl.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxEventProcessorImpl.java), [RabbitMQConfig.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/config/RabbitMQConfig.java).
- **Очередь карточных событий → Notification:** [RabbitMQConfig.java](../../../services/notification-service/src/main/java/com/processing/notification/config/RabbitMQConfig.java), [CardEventListener.java](../../../services/notification-service/src/main/java/com/processing/notification/listener/CardEventListener.java).
- **Счётчик повторов outbox:** [CardOutboxEventData.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/models/CardOutboxEventData.java), [OutboxJpaAdapter.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/repositories/OutboxJpaAdapter.java), [application.properties](../../../services/card-management/src/main/resources/application.properties).
- **Confirms и настройки потребителей:** [application.yml Switch](../../../services/switch/src/main/resources/application.yml), [application.properties Card Management](../../../services/card-management/src/main/resources/application.properties), [application.yaml Logger](../../../services/transaction-logger/src/main/resources/application.yaml), [application.yml Notification](../../../services/notification-service/src/main/resources/application.yml).
