#!/usr/bin/env python3
from flask import Flask, request, jsonify, render_template
from models import init_db, insert_messages, query_messages
from datetime import datetime
import os

app = Flask(__name__)
app.template_folder = os.path.join(os.path.dirname(__file__), 'templates')
app.config['MAX_CONTENT_LENGTH'] = 16 * 1024 * 1024

init_db()

@app.route('/api/health')
def health():
    return jsonify({'status': 'ok', 'time': datetime.now().isoformat()})

@app.route('/api/upload', methods=['POST'])
def upload():
    data = request.get_json(silent=True)
    if not data or 'messages' not in data or not isinstance(data['messages'], list):
        return jsonify({'error': 'invalid format'}), 400
    count = insert_messages(data['messages'])
    return jsonify({'synced': count})

@app.route('/api/messages')
def get_messages():
    page = request.args.get('page', 1, type=int)
    per_page = max(1, min(request.args.get('per_page', 50, type=int), 200))
    q = request.args.get('q')
    date = request.args.get('date')
    total, messages = query_messages(page=page, per_page=per_page, q=q, date=date)
    return jsonify({
        'total': total,
        'page': page,
        'per_page': per_page,
        'messages': messages,
    })

@app.route('/')
def index():
    return render_template('index.html')

if __name__ == '__main__':
    debug = os.environ.get('FLASK_DEBUG', '0') == '1'
    host = os.environ.get('FLASK_HOST', '0.0.0.0')
    print(f' * Lookatme server starting on http://{host}:5000')
    app.run(host=host, port=5000, debug=debug)
