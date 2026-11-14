package libcore

import (
 "context"
 "fmt"
 "io"
 "net"
 "net/http"
 "net/http/httptest"
 "testing"
 "time"

 "libcore/dnspolicy"
 box "github.com/sagernet/sing-box"
 "github.com/sagernet/sing-box/adapter/endpoint"
 "github.com/sagernet/sing-box/adapter/inbound"
 "github.com/sagernet/sing-box/adapter/outbound"
 boxservice "github.com/sagernet/sing-box/adapter/service"
 "github.com/sagernet/sing-box/boxapi"
 boxdns "github.com/sagernet/sing-box/dns"
 "github.com/sagernet/sing-box/dns/transport"
 "github.com/sagernet/sing-box/option"
 "github.com/sagernet/sing-box/protocol/direct"
 "github.com/sagernet/sing-box/protocol/vmess"
 "github.com/sagernet/sing/service"
)

func probeContractBox(t *testing.T, config string) *box.Box {
 t.Helper()
 dns := boxdns.NewTransportRegistry()
 transport.RegisterUDP(dns)
 dnspolicy.RegisterTransport(dns)
 in := inbound.NewRegistry(); vmess.RegisterInbound(in)
 out := outbound.NewRegistry(); direct.RegisterOutbound(out); vmess.RegisterOutbound(out)
 ctx := box.Context(context.Background(), in, out, endpoint.NewRegistry(), dns, boxservice.NewRegistry())
 ctx = service.ContextWithDefaultRegistry(ctx)
 var opts option.Options
 if err := opts.UnmarshalJSONContext(ctx, []byte(config)); err != nil { t.Fatal(err) }
 instance, err := box.New(box.Options{Options:opts, Context:ctx})
 if err != nil { t.Fatal(err) }
 t.Cleanup(func(){ _=instance.Close() })
 if err=instance.Start(); err != nil { t.Fatal(err) }
 return instance
}

// Real native WS/VMess handshake + the exact HTTP client Android UrlTest uses.
// No remote subscription credentials, Internet dependency or permissive JSON-only check.
func TestVmessProbeWsPathContract(t *testing.T) {
 target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request){ w.WriteHeader(204) }))
 defer target.Close()
 listener, err := net.Listen("tcp","127.0.0.1:0")
 if err != nil { t.Fatal(err) }
 port := listener.Addr().(*net.TCPAddr).Port
 _=listener.Close()
 const id="00000000-0000-4000-8000-000000000001"
 probeContractBox(t, fmt.Sprintf(`{
 "log":{"disabled":true},
 "inbounds":[{"type":"vmess","tag":"server","listen":"127.0.0.1","listen_port":%d,
 "users":[{"uuid":"%s","alterId":0}],"transport":{"type":"ws","path":"/source-only"}}],
 "outbounds":[{"type":"direct","tag":"direct"}],"route":{"final":"direct"}}`,port,id))
 for _, path := range []string{"/", "/source-only"} {
  t.Run(path,func(t *testing.T){
   clientBox:=probeContractBox(t,fmt.Sprintf(`{
    "log":{"disabled":true},
    "dns":{"servers":[{"type":"udp","tag":"unused-upstream","server":"127.0.0.1","server_port":53},
      {"type":"neko-policy","tag":"node-resolver","fallback":["unused-upstream"],"hosts":{"node.example.invalid":["127.0.0.1"]}}],"final":"node-resolver"},
    "outbounds":[{"type":"vmess","tag":"proxy","server":"node.example.invalid","server_port":%d,
     "uuid":"%s","security":"auto","alter_id":0,"packet_encoding":"",
     "transport":{"type":"ws","path":"%s","headers":{"Host":"source.example.invalid"}},
     "domain_resolver":{"server":"node-resolver","strategy":"ipv4_only"}}],"route":{"final":"proxy"}}`,port,id,path))
   client:=boxapi.CreateProxyHttpClient(clientBox,nil);defer client.CloseIdleConnections()
   for attempt:=0;attempt<2;attempt++ {
    ctx,cancel:=context.WithTimeout(context.Background(),2*time.Second)
    request,_:=http.NewRequestWithContext(ctx,"GET",target.URL,nil)
    response,err:=client.Do(request)
    if path=="/" { cancel();if response!=nil{response.Body.Close()};if err==nil{t.Fatal("wrong path unexpectedly passed WS handshake")};break }
    if err!=nil{cancel();t.Fatalf("correct path request %d failed: %v",attempt,err)}
    _,_=io.Copy(io.Discard,response.Body);response.Body.Close();cancel()
    if response.StatusCode!=204{t.Fatalf("expected204,got%d",response.StatusCode)}
   }
  })
 }
}
