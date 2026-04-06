import urllib.request
import json
import sys

req = urllib.request.Request('http://localhost:8080/api/task/43a7cfb939c0426398b529e9a1678606/preview/rebuild', method='POST')
req.add_header('Content-Type', 'application/json')
# We need to authenticate. Since it's spring boot, we might need a valid token.
