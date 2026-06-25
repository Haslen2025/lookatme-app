import sqlite3
import logging
import os
from datetime import datetime

logger = logging.getLogger(__name__)

DB_PATH = os.path.join(os.path.dirname(__file__), 'messages.db')

def get_db():
    conn = sqlite3.connect(DB_PATH, timeout=10)
    conn.row_factory = sqlite3.Row
    conn.execute('PRAGMA journal_mode=WAL')
    return conn

def init_db():
    conn = get_db()
    conn.execute('''
        CREATE TABLE IF NOT EXISTS messages (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            sender      TEXT NOT NULL,
            content     TEXT NOT NULL,
            msg_type    TEXT DEFAULT 'text',
            room_name   TEXT DEFAULT '',
            msg_time    DATETIME NOT NULL,
            sync_time   DATETIME DEFAULT CURRENT_TIMESTAMP,
            msg_id      TEXT UNIQUE
        )
    ''')
    conn.execute('CREATE INDEX IF NOT EXISTS idx_msg_time ON messages(msg_time DESC)')
    conn.execute('CREATE INDEX IF NOT EXISTS idx_msg_id ON messages(msg_id)')
    conn.commit()
    conn.close()

def insert_messages(messages):
    conn = get_db()
    inserted = 0
    for msg in messages:
        try:
            # Record total_changes before the insert so we can detect whether
            # INSERT OR IGNORE actually inserted a row (total_changes increments
            # by 1) or hit a UNIQUE conflict on msg_id and silently skipped it.
            before = conn.total_changes
            conn.execute('''
                INSERT OR IGNORE INTO messages (sender, content, msg_type, room_name, msg_time, msg_id)
                VALUES (?, ?, ?, ?, ?, ?)
            ''', (
                msg['sender'],
                msg['content'],
                msg.get('msg_type', 'text'),
                msg.get('room_name', ''),
                msg['msg_time'],
                msg['msg_id'],
            ))
            if conn.total_changes > before:
                inserted += 1
        except Exception:
            logger.warning('Failed to insert message', exc_info=True)
    conn.commit()
    conn.close()
    return inserted

def escape_like(s):
    """Escape % and _ characters so they are treated literally in LIKE patterns."""
    return s.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_')


def query_messages(page=1, per_page=50, q=None, date=None):
    conn = get_db()
    conditions = []
    params = []

    if q:
        escaped = escape_like(q)
        conditions.append('(sender LIKE ? OR content LIKE ?)')
        params.extend([f'%{escaped}%', f'%{escaped}%'])
    if date:
        conditions.append('date(msg_time) = ?')
        params.append(date)

    where = 'WHERE ' + ' AND '.join(conditions) if conditions else ''
    escape_clause = " ESCAPE '\\'" if q else ''
    total = conn.execute(f'SELECT COUNT(*) FROM messages {where}{escape_clause}', params).fetchone()[0]

    offset = (page - 1) * per_page
    rows = conn.execute(
        f'SELECT * FROM messages {where} ORDER BY msg_time DESC LIMIT ? OFFSET ?{escape_clause}',
        params + [per_page, offset]
    ).fetchall()

    conn.close()
    return total, [dict(r) for r in rows]
