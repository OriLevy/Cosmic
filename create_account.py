import getpass
import hashlib
import sys
from datetime import date, datetime

import json
import urllib.request
import urllib.error


REGISTER_URL = 'http://127.0.0.1:8585/register'


def get_register_url() -> str:
    return REGISTER_URL


def should_use_bcrypt() -> bool:
    # Use bcrypt consistently; server accepts bcrypt regardless of migration flag
    return True


def hash_sha512(password: str) -> str:
    # Match Java: UTF-8 bytes -> hex lowercase without spaces
    digest = hashlib.sha512(password.encode('utf-8')).hexdigest()
    return digest.lower()


def hash_bcrypt(password: str) -> str:
    try:
        import bcrypt  # type: ignore
    except ImportError as e:
        raise RuntimeError("bcrypt is required for hashing. Install with: pip install bcrypt") from e
    # Java uses gensalt(12)
    salt = bcrypt.gensalt(rounds=12)
    return bcrypt.hashpw(password.encode('utf-8'), salt).decode('utf-8')


def prompt_credentials() -> tuple[str, str]:
    username = input('Enter username: ').strip()
    if not username:
        print('Username cannot be empty.', file=sys.stderr)
        sys.exit(1)
    if len(username) > 13:
        print('Username must be at most 13 characters.', file=sys.stderr)
        sys.exit(1)

    password = getpass.getpass('Enter password: ')
    if not password:
        print('Password cannot be empty.', file=sys.stderr)
        sys.exit(1)
    return username, password


def call_register(url: str, username: str, password: str):
    data = json.dumps({"username": username, "password": password}).encode('utf-8')
    req = urllib.request.Request(url, data=data, headers={'Content-Type': 'application/json'}, method='POST')
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            body = resp.read().decode('utf-8')
            return resp.getcode(), body
    except urllib.error.HTTPError as e:
        err_body = e.read().decode('utf-8', errors='ignore')
        return e.code, err_body
    except urllib.error.URLError as e:
        print(f'Failed to reach registration endpoint at {url}: {e}', file=sys.stderr)
        sys.exit(2)


def username_exists(cur, username: str) -> bool:
    cur.execute("SELECT 1 FROM accounts WHERE name = %s LIMIT 1", (username,))
    return cur.fetchone() is not None


def insert_account(cur, username: str, password_hash: str):
    # Minimal fields required matching server auto-register behavior
    # INSERT INTO accounts (name, password, birthday, tempban)
    # Defaults will cover the rest, including non-GM related flags
    bday = date(2005, 5, 11)  # DefaultDates.getBirthday()
    tempban = datetime(2005, 5, 11, 0, 0, 0)  # DefaultDates.getTempban()
    cur.execute(
        """
        INSERT INTO accounts (name, password, birthday, tempban)
        VALUES (%s, %s, %s, %s)
        """,
        (username, password_hash, bday, tempban),
    )


def main():
    url = get_register_url()
    use_bcrypt = should_use_bcrypt()

    username, password = prompt_credentials()
    password_hash = hash_bcrypt(password) if use_bcrypt else hash_sha512(password)

    # Endpoint handles hashing; send plain password
    status, body = call_register(url, username, password)
    if status == 201:
        print(f'Account "{username}" created successfully.')
    elif status == 409:
        print('Username already exists.', file=sys.stderr)
        sys.exit(1)
    elif status == 400:
        print(f'Invalid request: {body}', file=sys.stderr)
        sys.exit(1)
    else:
        print(f'Registration failed ({status}): {body}', file=sys.stderr)
        sys.exit(3)


if __name__ == '__main__':
    main()


