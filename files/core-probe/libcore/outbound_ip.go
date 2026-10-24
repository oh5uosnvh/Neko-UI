package libcore

import (
    "context"
    "encoding/json"
    "io"
    "net"
    "net/http"
    "net/netip"
    "strings"
    "sync/atomic"
    "time"

    "github.com/sagernet/sing/common/metadata"
)

type OutboundIpResult struct { URL string `json:"url"`; Body string `json:"body"`; Delay int64 `json:"delay"`; Chains []string `json:"chains"`; CoreEpoch uint64 `json:"coreEpoch"`; RouteVersion uint64 `json:"routeVersion"`; Error string `json:"error,omitempty"` }
var outboundIpEpoch atomic.Uint64

func containsPublicAddress(body string) bool {
    for _, token := range strings.FieldsFunc(body, func(r rune) bool { return !(r=='.'||r==':'||r>='0'&&r<='9'||r>='a'&&r<='f'||r>='A'&&r<='F') }) {
        if addr, err := netip.ParseAddr(token); err == nil { addr=addr.Unmap(); if addr.IsGlobalUnicast() && !addr.IsPrivate() { return true } }
    }
    return false
}

// OutboundIpJSON is the gomobile-safe core probe. JSON keeps the AIDL surface
// stable while the actual socket is dialed by the running sing-box outbound.
func OutboundIpJSON(i *BoxInstance, proxyName, urlsJSON string, timeout int64) string {
    epoch := outboundIpEpoch.Add(1); result:=&OutboundIpResult{CoreEpoch:epoch}
    if i==nil || i.Box==nil { result.Error="core not started"; b,_:=json.Marshal(result); return string(b) }
    outbound,ok:=i.Outbound().Outbound(proxyName); if !ok||outbound==nil { outbound=i.Outbound().Default() }
    if outbound==nil { result.Error="outbound not found"; b,_:=json.Marshal(result); return string(b) }
    var urls []string; if json.Unmarshal([]byte(urlsJSON),&urls)!=nil { result.Error="invalid urls"; b,_:=json.Marshal(result); return string(b) }
    limit:=time.Duration(timeout)*time.Millisecond; if limit<=0 {limit=6*time.Second}; ctx,cancel:=context.WithTimeout(context.Background(),limit); defer cancel()
    for _,target:=range urls { if strings.TrimSpace(target)=="" {continue}; req,err:=http.NewRequestWithContext(ctx,http.MethodGet,target,nil); if err!=nil {continue}; req.Header.Set("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36"); started:=time.Now(); tr:=&http.Transport{ForceAttemptHTTP2:true,DialContext:func(c context.Context,network,address string)(net.Conn,error){return outbound.DialContext(c,network,metadata.ParseSocksaddr(address))}}; client:=&http.Client{Transport:tr}; resp,err:=client.Do(req); if err!=nil {client.CloseIdleConnections();continue}; body,e:=io.ReadAll(io.LimitReader(resp.Body,8192));resp.Body.Close();client.CloseIdleConnections(); if e!=nil||resp.StatusCode!=http.StatusOK||!containsPublicAddress(string(body)){continue}; result.URL=target;result.Body=string(body);result.Delay=time.Since(started).Milliseconds();result.Chains=[]string{outbound.Tag()};break }
    if result.URL=="" {result.Error="all outbound IP sources failed"}; b,_:=json.Marshal(result);return string(b)
}
