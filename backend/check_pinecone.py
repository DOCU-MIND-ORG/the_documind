import urllib.request
import json
import os

api_key = "pcsk_UemqN_8JRj31n6i2PBi1hzVovyPywUHt8qnSQ3LUB2uymZbp4zfQNRmU6YcHRANNPVfmf"
host = "https://docmind-integrated-g579lta.svc.aped-4627-b74a.pinecone.io"

url = f"{host}/describe_index_stats"
headers = {
    "Api-Key": api_key,
    "Content-Type": "application/json"
}

req = urllib.request.Request(url, headers=headers)
try:
    with urllib.request.urlopen(req) as response:
        print(response.read().decode('utf-8'))
except urllib.error.HTTPError as e:
    print(f"HTTP Error: {e.code}")
    print(e.read().decode('utf-8'))
