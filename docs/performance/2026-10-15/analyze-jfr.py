#!/usr/bin/env python3
"""Summarize JFR execution samples and weighted allocation samples; no claim of wall-time percentages."""
import collections,json,sys,re
from pathlib import Path
p=Path(sys.argv[1]); events=json.loads(p.read_text())['recording']['events']
def frames(v):
    return [f["method"]["type"]["name"].replace('/','.')+'.'+f['method']['name'] for f in (v.get('stackTrace') or {}).get('frames',[])]
cpu=[];alloc=[];gc=[]
for e in events:
    if e['type']=='jdk.ExecutionSample': cpu.append(e['values'])
    elif e['type']=='jdk.ObjectAllocationSample': alloc.append(e['values'])
    elif e['type']=='jdk.GarbageCollection': gc.append(e['values'])
leaf=collections.Counter();threads=collections.Counter();inclusive=collections.Counter();allocation=collections.Counter();allocsite=collections.Counter();owners=collections.Counter();undertow_lines=collections.Counter()
for v in cpu:
    fs=frames(v)
    raw=(v.get('stackTrace') or {}).get('frames',[])
    if fs and fs[0]=='io.undertow.server.protocol.http.HttpReadListener.handleEvent':
        undertow_lines[raw[0].get('lineNumber')]+=1
    if fs:leaf[fs[0]]+=1
    threads[(v.get('sampledThread') or {}).get('javaName','unknown')]+=1
    # Attribute to the nearest recognized library frame, never an outer filter
    # merely because it appears below the whole request pipeline in the stack.
    owner='JDK / other'
    for f in fs:
        if f.startswith('org.springframework.web.'):
            owner='Spring MVC handler lookup' if any('AbstractHandlerMethodMapping.lookupHandlerMethod' in x for x in fs) else 'Spring web other'
            break
        if f.startswith('org.springframework.security.'):owner='Spring Security';break
        if f.startswith('io.micrometer.'):owner='Micrometer';break
        if f.startswith('com.echo.'):owner='Echo';break
        if f.startswith(('io.undertow.','org.xnio.','org.jboss.threads.')):owner='Undertow / XNIO';break
        if f.startswith('wiremock.org.eclipse.jetty.'):owner='Jetty';break
        if f.startswith('com.github.tomakehurst.wiremock.'):owner='WireMock';break
        if f.startswith('org.springframework.'):owner='Spring other';break
        if f.startswith('wiremock.'):owner='WireMock shaded dependencies';break
    owners[owner]+=1
    # Inclusive counts overlap; never sum these percentages.
    patterns={'MVC handler lookup':'AbstractHandlerMethodMapping.lookupHandlerMethod','MVC handler introspection':'HandlerMappingIntrospector.', 'Spring Security':'org.springframework.security.', 'Micrometer':'io.micrometer.', 'Echo pipeline':'com.echo.pipeline.', 'Echo diagnostics':'com.echo.diagnostics.', 'Match chain formatting':'MatchDescriptionBuilder.', 'HTTP request adaptation':'UniversalMockController.toMockRequest','WireMock matching':'com.github.tomakehurst.wiremock.matching.'}
    for k,pattern in patterns.items():
        if any(pattern in f for f in fs): inclusive[k]+=1
for v in alloc:
    w=v.get('weight',0); allocation[v['objectClass']['name'].replace('/','.')]+=w
    fs=frames(v)
    if fs:allocsite[fs[0]]+=w
res={'execution_samples':len(cpu),'nearest_recognized_frame':dict(owners),'undertow_handle_event_leaf_lines':dict(undertow_lines),'sampled_threads':threads.most_common(),'leaf_methods':leaf.most_common(35),'inclusive_stack_counts_OVERLAP':dict(inclusive),'allocation_sample_count':len(alloc),'allocation_weight_bytes':sum(allocation.values()),'allocation_classes':allocation.most_common(20),'allocation_leaf_sites':allocsite.most_common(25),'gc_events':[{k:v.get(k) for k in ('name','cause','duration','sumOfPauses','longestPause')} for v in gc]}
print(json.dumps(res,indent=2))
