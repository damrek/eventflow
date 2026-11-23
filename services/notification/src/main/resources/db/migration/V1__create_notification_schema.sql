-- Notification schema, owned exclusively by the Notification service.
-- One row per user-facing message; processed_message is the inbox that makes at-least-once
-- delivery produce exactly one notification per (consumer, event).

create table notification (
    id         uuid         primary key,
    user_id    varchar(100) not null,
    type       varchar(40)  not null,
    content    varchar(500) not null,
    status     varchar(20)  not null,
    event_id   uuid         not null,
    created_at timestamptz  not null,
    constraint ck_notification_status check (status in ('UNREAD', 'READ')),
    constraint uk_notification_event unique (event_id)
);

-- The demo reads one user's mailbox, newest first.
create index ix_notification_user_created on notification (user_id, created_at desc);

create table processed_message (
    consumer_name varchar(100) not null,
    event_id      uuid         not null,
    processed_at  timestamptz  not null,
    constraint uk_processed_message_consumer_event unique (consumer_name, event_id)
);
