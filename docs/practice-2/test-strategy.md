# Тестовая стратегия СМП

## Цель тестирования

Убедиться, что платёжная операция проходит через СМП правильно: запрос принимается, карта проверяется, по операции принимается решение, при одобрении резервируются средства, а результат сохраняется и отображается.

## Объекты тестирования

- Gateway;
- Switch;
- Authorization;
- Card Management;
- Terminal Simulator;
- Transaction Logger;
- Web Dashboard.

## Область и границы тестирования

Для Authorization и Card Management готовится подробный test-design. Для Gateway, Switch, Terminal Simulator, Transaction Logger и Web Dashboard используются чек-листы.

Не планируются проверки производительности, безопасности.

## Подход к тестированию

Подход риск-ориентированный: сначала проверяются операции, связанные с деньгами и решением по платежу (подробная проверка Authorization и Card Management), затем передача данных между сервисами (чек-листы).

## Уровни проверок

- **Сервисный/API** — проверка отдельных API Authorization и Card Management и их правил.
- **Интеграционный** — проверка обмена запросами между Gateway, Switch, Authorization, Card Management и Logger.
- **Системный** — проверка полного сценария от Terminal Simulator до Web Dashboard.
