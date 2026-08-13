UPDATE fin_ex_chat_session_t
SET last_read_seq = latest_message_seq
WHERE status <> 'DELETED'
  AND latest_message_seq > last_read_seq;
