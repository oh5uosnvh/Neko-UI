#!/usr/bin/env python3
"""Run Git with an ephemeral GitHub credential header, never save credentials."""
import base64,os,subprocess,sys
args=sys.argv[1:]
if not args:raise SystemExit('Git arguments required')
environment=os.environ.copy()
token=environment.get('SING_BOX_CLONE_TOKEN') or environment.get('GITHUB_TOKEN')
if token:
    count=int(environment.get('GIT_CONFIG_COUNT','0'))
    environment['GIT_CONFIG_COUNT']=str(count+1)
    environment[f'GIT_CONFIG_KEY_{count}']='http.https://github.com/.extraheader'
    environment[f'GIT_CONFIG_VALUE_{count}']='Authorization: Basic '+base64.b64encode(('x-access-token:'+token).encode()).decode()
environment['GIT_TERMINAL_PROMPT']='0'
raise SystemExit(subprocess.call(['git',*args],env=environment))
