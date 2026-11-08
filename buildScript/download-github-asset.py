#!/usr/bin/env python3
"""Download an official GitHub asset; auth is sent only to api.github.com."""
import os, sys, urllib.request, urllib.error

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

url, destination = sys.argv[1:3]
if not url.startswith('https://api.github.com/repos/'):
    raise SystemExit('Only official GitHub API asset endpoints are allowed')
headers = {'User-Agent': 'Neko-UI-build', 'Accept': 'application/octet-stream'}
token = os.environ.get('GITHUB_TOKEN', '')
if token:
    headers['Authorization'] = 'Bearer ' + token
try:
    response = urllib.request.build_opener(NoRedirect).open(
        urllib.request.Request(url, headers=headers), timeout=120)
except urllib.error.HTTPError as error:
    if error.code not in (301, 302, 303, 307, 308):
        raise SystemExit('GitHub asset API returned HTTP ' + str(error.code))
    location = error.headers['Location']
    if not location.startswith('https://'):
        raise SystemExit('Non-HTTPS asset redirect refused')
    # Never forward the API token to redirected blob/storage/CDN servers.
    response = urllib.request.urlopen(urllib.request.Request(location,
        headers={'User-Agent': 'Neko-UI-build'}), timeout=120)
with response, open(destination, 'wb') as output:
    while True:
        chunk = response.read(1024 * 1024)
        if not chunk:
            break
        output.write(chunk)
