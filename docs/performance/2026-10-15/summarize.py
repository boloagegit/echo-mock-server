#!/usr/bin/env python3
import json,re,os,sys
from pathlib import Path
r=Path(sys.argv[1])
def percentile_ms(text, pct):
    number = str(pct)
    suffix = '0*' if '.' in number else r'(?:\.0+)?'
    m=re.search(rf"^\s+{re.escape(number)}{suffix}%\s+([\d.]+)(us|ms|s|m)\s*$",text,re.M)
    if not m:return None
    value=float(m[1]);return value*{'us':0.001,'ms':1,'s':1000,'m':60000}[m[2]]

res={}
for run in sorted(r.iterdir()):
    if not run.is_dir():continue
    rows={}
    for p in sorted(run.glob('wrk-*.txt')):
        t=p.read_text();m=re.search(r'(\d+) requests in ([\d.]+)([smh])',t);rate=re.search(r'Requests/sec:\s+([\d.]+)',t)
        if not m or not rate:continue
        case=p.stem[4:];ct=[]
        for suffix in ['before','after']:
            f=run/f'cpu-{case}-{suffix}.txt'
            if f.exists():
                v=f.read_text().rsplit(')',1)[1].split();ct.append((int(v[11])+int(v[12]))/os.sysconf('SC_CLK_TCK'))
        cputime=ct[1]-ct[0] if len(ct)==2 else None
        sock=re.search(r'Socket errors: connect (\d+), read (\d+), write (\d+), timeout (\d+)',t)
        http=re.search(r'Non-2xx or 3xx responses: (\d+)',t)
        rows[case]={'requests':int(m[1]),'seconds':float(m[2])*{'s':1,'m':60,'h':3600}[m[3]],'rps':float(rate[1]),'p50_ms':percentile_ms(t,50),'p99_ms':percentile_ms(t,99),'p999_ms':percentile_ms(t,99.9),'server_cpu_seconds':round(cputime,3) if cputime is not None else None,'server_cpu_us_per_request':round(cputime*1e6/int(m[1]),2) if cputime is not None else None,'http_errors':int(http[1]) if http else 0,'socket_errors':sum(map(int,sock.groups())) if sock else 0}
    res[run.name]={'done':(run/'DONE').exists(),'cases':rows}
print(json.dumps(res,indent=2))
