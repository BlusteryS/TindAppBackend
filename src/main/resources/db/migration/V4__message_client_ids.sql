ALTER TABLE messages ADD COLUMN IF NOT EXISTS client_message_id TEXT;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'messages'
          AND column_name = 'data'
    ) THEN
        UPDATE messages
        SET client_message_id = COALESCE(
            client_message_id,
            NULLIF(data->>'client_message_id', ''),
            NULLIF(data->>'clientMessageId', '')
        );
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_messages_chat_client_message_id
    ON messages(chat_id, client_message_id)
    WHERE client_message_id IS NOT NULL;
