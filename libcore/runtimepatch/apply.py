#!/usr/bin/env python3
"""Idempotent, exact-match injection for pinned sing-box 0b7a2fc.
Run: python3 libcore/runtimepatch/apply.py /path/to/sing-box
Never invokes git, reload, or changes dependency/version metadata.
"""
import pathlib,sys,json,hashlib,subprocess
verify = sys.argv[1] == '--verify-existing'
root=pathlib.Path(sys.argv[2] if verify else sys.argv[1]); here=pathlib.Path(__file__).parent
if verify:
    try:
        manifest=json.loads((root/'.git/neko-runtime-routing.json').read_text())
        head=subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip()
        if head != sys.argv[3] or manifest['commit'] != head: raise ValueError('base commit mismatch')
        paths=set(subprocess.check_output(['git','-C',str(root),'diff','--name-only','HEAD'],text=True).splitlines())
        paths.update(subprocess.check_output(['git','-C',str(root),'ls-files','--others','--exclude-standard'],text=True).splitlines())
        if paths != set(manifest['files']): raise ValueError('unexpected dependency changes')
        for name,digest in manifest['files'].items():
            if hashlib.sha256((root/name).read_bytes()).hexdigest() != digest: raise ValueError('changed patch: '+name)
    except Exception as e: raise SystemExit('Runtime patch verification failed: '+str(e))
    print('Verified exact installed runtime patch');sys.exit(0)
MARK='// neko-runtime-routing-v1'
if MARK in (root/'route/router.go').read_text():
    # Verify the existing installation rather than trusting a lone marker.
    for source, target in [('adapter_runtime_routing.go.txt','adapter/runtime_routing.go'),('route_runtime_routing.go.txt','route/runtime_routing.go')]:
        if (root/target).read_text() != (here/source).read_text():
            raise SystemExit('Runtime routing installation mismatch: ' + target)
    for source, target in [('adapter_runtime_routing_test.go.txt','adapter/runtime_routing_test.go'),('box_runtime_routing_test.go.txt','runtime_routing_test.go')]:
        (root/target).write_text((here/source).read_text())
    print('runtime routing v1 already installed');sys.exit(0)
changes={}
def edit(name,old,new,count=1):
    s=changes.get(name,(root/name).read_text())
    if s.count(old)!=count: raise SystemExit(f'Pinned source mismatch: {name}: expected {count} matches')
    changes[name]=s.replace(old,new)
edit('dns/client.go','c.storeCache(transport, question, response, timeToLive)','c.storeCache(transport, question, response.Copy(), timeToLive)')
edit('box.go','ctx = service.ContextWithDefaultRegistry(ctx)\n\n\tendpointRegistry', 'ctx = service.ContextWithDefaultRegistry(ctx)\n\tservice.MustRegister[*adapter.RuntimeRoutingState](ctx, new(adapter.RuntimeRoutingState))\n\n\tendpointRegistry')
edit('route/router.go','"runtime"','"runtime"\n "sync"')
edit('route/router.go','type Router struct {','type Router struct {\n '+MARK+'\n runtimeUpdateMu sync.Mutex\n runtimeClosed bool\n runtimeRouting *adapter.RuntimeRoutingState\n runtimeRouteOptions option.RouteOptions\n runtimeDNSOptions option.DNSOptions')
edit('route/router.go','ctx:               ctx,','ctx:               ctx,\n runtimeRouting: service.FromContext[*adapter.RuntimeRoutingState](ctx),\n runtimeRouteOptions: options,\n runtimeDNSOptions: dnsOptions,')
edit('route/router.go','func (r *Router) Close() error {','func (r *Router) Close() error {\n r.runtimeUpdateMu.Lock()\n defer r.runtimeUpdateMu.Unlock()\n r.runtimeClosed=true\n r.runtimeRouting.Close()')
edit('route/router.go','r.started = true\n\t\treturn nil','r.started = true\n initial:=r.dns.(interface{RuntimeDNSInitial() *adapter.RuntimeRoutingSnapshot}).RuntimeDNSInitial()\n initial.Rules=r.rules;initial.Final=r.outbound.Default()\n if _,err:=r.runtimeRouting.Publish(initial);err!=nil{return err}\n\t\treturn nil')
edit('route/router.go','return r.rules','ctx,release,err:=r.runtimeRouting.Pin(r.ctx)\n if err!=nil{return nil}\n defer release()\n if p:=adapter.RuntimeRoutingFromContext(ctx);p!=nil{\n result:=make([]adapter.Rule,len(p.Rules))\n for i,it:=range p.Rules{result[i]=runtimeTrackerRule(ctx,it)}\n return result\n }\n return append([]adapter.Rule(nil),r.rules...)')
# Pin for the whole routing operation, including actions, default selection and
# tracker creation. Nested DNS reuses ctx, so no reader-lock nesting deadlock.
for signature in [
 'func (r *Router) routeConnection(ctx context.Context, conn net.Conn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) error {',
 'func (r *Router) routePacketConnection(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) error {']:
 edit('route/route.go',signature,signature+'\n var pinErr error\n var release func()\n ctx,release,pinErr=r.runtimeRouting.Pin(ctx)\n if pinErr!=nil{return pinErr}\n defer release()')
edit('route/route.go','func (r *Router) PreMatch(metadata adapter.InboundContext) error {\n\tselectedRule, _, _, _, err := r.matchRule(r.ctx,', 'func (r *Router) PreMatch(metadata adapter.InboundContext) error {\n ctx,release,pinErr:=r.runtimeRouting.Pin(r.ctx)\n if pinErr!=nil{return pinErr}\n defer release()\n\tselectedRule, _, _, _, err := r.matchRule(ctx,')
edit('route/route.go','match:\n\tfor currentRuleIndex, currentRule := range r.rules {','rules:=r.rules\n if p:=adapter.RuntimeRoutingFromContext(ctx);p!=nil{rules=p.Rules}\n match:\n for currentRuleIndex, currentRule := range rules {')
edit('route/route.go','defaultOutbound := r.outbound.Default()','defaultOutbound := r.outbound.Default()\n if p:=adapter.RuntimeRoutingFromContext(ctx);p!=nil{defaultOutbound=p.Final}',2)
edit('route/route.go','conn = tracker.RoutedConnection(ctx, conn, metadata, selectedRule, selectedOutbound)','conn = tracker.RoutedConnection(ctx, conn, metadata, runtimeTrackerRule(ctx,selectedRule), selectedOutbound)')
edit('route/route.go','conn = r.nekoTracker.RoutedConnection(ctx, conn, metadata, selectedRule, selectedOutbound)','conn = r.nekoTracker.RoutedConnection(ctx, conn, metadata, runtimeTrackerRule(ctx,selectedRule), selectedOutbound)')
edit('route/route.go','conn = tracker.RoutedPacketConnection(ctx, conn, metadata, selectedRule, selectedOutbound)','conn = tracker.RoutedPacketConnection(ctx, conn, metadata, runtimeTrackerRule(ctx,selectedRule), selectedOutbound)')
edit('route/route.go','conn = r.nekoTracker.RoutedPacketConnection(ctx, conn, metadata, selectedRule, selectedOutbound)','conn = r.nekoTracker.RoutedPacketConnection(ctx, conn, metadata, runtimeTrackerRule(ctx,selectedRule), selectedOutbound)')
edit('route/dns.go','func (r *Router) hijackDNSStream(ctx context.Context, conn net.Conn, metadata adapter.InboundContext) error {','func (r *Router) hijackDNSStream(ctx context.Context, conn net.Conn, metadata adapter.InboundContext) error {\n ctx=adapter.WithoutRuntimeRouting(ctx)')
edit('route/dns.go','func (r *Router) hijackDNSPacket(ctx context.Context, conn N.PacketConn, packetBuffers []*N.PacketBuffer, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) error {','func (r *Router) hijackDNSPacket(ctx context.Context, conn N.PacketConn, packetBuffers []*N.PacketBuffer, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) error {\n ctx=adapter.WithoutRuntimeRouting(ctx)')
edit('dns/router.go','type Router struct {','type Router struct {\n runtimeRouting *adapter.RuntimeRoutingState\n runtimeClientOptions ClientOptions')
edit('dns/router.go','ctx:                   ctx,','ctx:                   ctx,\n runtimeRouting: service.FromContext[*adapter.RuntimeRoutingState](ctx),')
edit('dns/router.go','router.client = NewClient(ClientOptions{','router.runtimeClientOptions = ClientOptions{')
edit('dns/router.go','Logger: router.logger,\n\t})','Logger: router.logger,\n\t}\n router.client = NewClient(router.runtimeClientOptions)')
edit('dns/router.go','for ; currentRuleIndex < len(r.rules); currentRuleIndex++ {\n\t\tcurrentRule := r.rules[currentRuleIndex]','rules:=r.rules\n if p:=adapter.RuntimeRoutingFromContext(ctx);p!=nil{rules=p.DNSRules}\n for ; currentRuleIndex < len(rules); currentRuleIndex++ {\n\t\tcurrentRule := rules[currentRuleIndex]')
edit('dns/router.go','return r.transport.Default(), nil, -1','if p:=adapter.RuntimeRoutingFromContext(ctx);p!=nil{return p.DNSFinal,nil,-1}\n return r.transport.Default(), nil, -1')
for sig in [
 'func (r *Router) Exchange(ctx context.Context, message *mDNS.Msg, options adapter.DNSQueryOptions) (*mDNS.Msg, error) {',
 'func (r *Router) Lookup(ctx context.Context, domain string, options adapter.DNSQueryOptions) ([]netip.Addr, error) {']:
 edit('dns/router.go',sig,sig+'\n var pinErr error\n var release func()\n ctx,release,pinErr=r.runtimeRouting.Pin(ctx)\n if pinErr!=nil{return nil,pinErr}\n defer release()\n client:=r.client\n strategy:=r.defaultDomainStrategy\n if p:=adapter.RuntimeRoutingFromContext(ctx);p!=nil{client=p.DNSClient;strategy=p.DNSStrategy}')
# Only request bodies use their pinned client's cache/strategy. ClearCache and
# reverse mapping remain live-router APIs.
s=changes['dns/router.go']; a=s.index('func (r *Router) Exchange('); b=s.index('func isAddressQuery(',a)
s=s[:a]+s[a:b].replace('r.client.','client.').replace('r.defaultDomainStrategy','strategy')+s[b:]
s=s.replace('strategy:=strategy','strategy:=r.defaultDomainStrategy')
changes['dns/router.go']=s
changes['dns/runtime_routing.go']='''package dns
import (
 "github.com/sagernet/sing-box/adapter"
 "github.com/sagernet/sing-box/option"
)
func (r *Router) RuntimeDNSInitial() *adapter.RuntimeRoutingSnapshot {
 return &adapter.RuntimeRoutingSnapshot{DNSRules:r.rules,DNSFinal:r.transport.Default(),DNSClient:r.client,DNSStrategy:r.defaultDomainStrategy}
}
func (r *Router) NewRuntimeDNSClient(options option.DNSOptions) adapter.DNSClient {
 opts:=r.runtimeClientOptions
 // A new cache per generation prevents old in-flight replies repopulating the
 // new generation after publication. RDRC persisted rejects are policy-specific.
 opts.RDRC=nil
 client:=NewClient(opts)
 client.Start()
 return client
}
'''
for src,dst in [('adapter_runtime_routing.go.txt','adapter/runtime_routing.go'),('route_runtime_routing.go.txt','route/runtime_routing.go'),('adapter_runtime_routing_test.go.txt','adapter/runtime_routing_test.go'),('box_runtime_routing_test.go.txt','runtime_routing_test.go')]:changes[dst]=(here/src).read_text()
# Exact-match checks completed before any source is written.
for name,s in changes.items():(root/name).write_text(s)
print('Injected runtime routing v1 into',root)
if (root/'.git').is_dir():
    paths=set(subprocess.check_output(['git','-C',str(root),'diff','--name-only','HEAD'],text=True).splitlines())
    paths.update(subprocess.check_output(['git','-C',str(root),'ls-files','--others','--exclude-standard'],text=True).splitlines())
    manifest={'commit':subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip(), 'files':{p:hashlib.sha256((root/p).read_bytes()).hexdigest() for p in paths}}
    (root/'.git/neko-runtime-routing.json').write_text(json.dumps(manifest,sort_keys=True))
