package x365

import (
	"bytes"
	"context"
	"io"
	"net"
	"strconv"
	"testing"
	"time"

	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	standard "github.com/sagernet/sing-box/protocol/vless"
	M "github.com/sagernet/sing/common/metadata"
)

func TestVLESSMarkerDispatchWire(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	_, port, _ := net.SplitHostPort(listener.Addr().String())
	p, _ := strconv.Atoi(port)
	options := option.VLESSOutboundOptions{ServerOptions: option.ServerOptions{Server: "127.0.0.1", ServerPort: uint16(p)}, UUID: "00112233-4455-6677-8899-aabbccddeeff#x365"}
	logger := log.NewNOPFactory().Logger()
	ob, err := newVLESSOutbound(context.Background(), nil, logger, "fixture", options)
	if err != nil {
		t.Fatal(err)
	}
	defer ob.(interface{ Close() error }).Close()
	if _, ok := ob.(*Outbound); !ok || ob.Type() != "vless" {
		t.Fatal("private UUID not dispatched as VLESS")
	}
	received := make(chan []byte, 1)
	go func() {
		c, e := listener.Accept()
		if e != nil {
			received <- nil
			return
		}
		defer c.Close()
		c.SetDeadline(time.Now().Add(3 * time.Second))
		b := make([]byte, 30)
		_, e = io.ReadFull(c, b)
		if e != nil {
			received <- nil
			return
		}
		received <- b
		c.Write(append([]byte("X365\x01"), []byte("ok")...))
	}()
	c, err := ob.DialContext(context.Background(), "tcp", M.ParseSocksaddr("1.2.3.4:443"))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err = c.Write([]byte("p")); err != nil {
		t.Fatal(err)
	}
	got := <-received
	expected := []byte{0x58, 0x33, 0x36, 0x35, 1, 1, 0, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff, 1, 0xbb, 1, 1, 2, 3, 4, 'p'}
	if !bytes.Equal(got, expected) {
		t.Fatalf("wrong request %x", got)
	}
	response := make([]byte, 2)
	if _, err = io.ReadFull(c, response); err != nil || string(response) != "ok" {
		t.Fatalf("response %q %v", response, err)
	}
	options.UUID = "00112233-4455-6677-8899-aabbccddeeff"
	plain, err := newVLESSOutbound(context.Background(), nil, logger, "plain", options)
	if err != nil {
		t.Fatal(err)
	}
	defer plain.(interface{ Close() error }).Close()
	if _, ok := plain.(*standard.Outbound); !ok {
		t.Fatal("ordinary VLESS intercepted")
	}
}
func TestMarkerOnlyAtEnd(t *testing.T) {
	for _, value := range []string{"secret", "secret#x365-tail"} {
		clean, on := ParseMarker(value)
		if on || clean != value {
			t.Fatal("ordinary credential changed")
		}
	}
}
