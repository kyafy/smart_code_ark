import hmac
import hashlib
import base64
import json
import base64

def b64url(b):
    return base64.urlsafe_b64encode(b).rstrip(b'=').decode('utf-8')

header = b64url(json.dumps({"alg": "HS256", "typ": "JWT"}).encode('utf-8'))
payload = b64url(json.dumps({"userId": "1", "exp": 2000000000}).encode('utf-8'))

sig = b64url(hmac.new(b'change-me', (header + "." + payload).encode('utf-8'), hashlib.sha256).digest())
print(f"{header}.{payload}.{sig}")
