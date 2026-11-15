package xhttp

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/md5"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
)

// Real loopback TCP, with an independently implemented peer: do not merely
// round-trip two copies of ClientConn, which would hide common framing bugs.
func TestCipherLocalTCPRoundTrip(t *testing.T) {
	const password = "fixture-key"
	const token = "fixture-token"
	sess := bytes.Repeat([]byte{0x42}, 68)
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	listener.(*net.TCPListener).SetDeadline(time.Now().Add(5 * time.Second))
	done := make(chan error, 1)
	go func() {
		peer, err := listener.Accept()
		if err != nil {
			done <- err
			return
		}
		defer peer.Close()
		peer.SetDeadline(time.Now().Add(5 * time.Second))
		prefix := make([]byte, 128+16+1+len(token)+1+16)
		if _, err = io.ReadFull(peer, prefix); err != nil {
			done <- err
			return
		}
		magic := md5.Sum([]byte(password + "do not hack this protocol please"))
		if !bytes.Equal(prefix[:68], sess) || !bytes.Equal(prefix[128:144], magic[:]) || int(prefix[144]) != len(token) || string(prefix[145:145+len(token)]) != token || prefix[145+len(token)] != 16 {
			done <- fmt.Errorf("invalid request prelude")
			return
		}
		key := md5.Sum([]byte(password))
		block, _ := aes.NewCipher(key[:])
		reader := &cipher.StreamReader{S: cipher.NewCTR(block, prefix[len(prefix)-16:]), R: peer}
		// SOCKS5 domain address: type, length, host, big-endian port.
		head := make([]byte, 2+len("example.test")+2)
		if _, err = io.ReadFull(reader, head); err != nil {
			done <- err
			return
		}
		if head[0] != 3 || head[1] != byte(len("example.test")) || string(head[2:len(head)-2]) != "example.test" || binary.BigEndian.Uint16(head[len(head)-2:]) != 443 {
			done <- fmt.Errorf("destination = %x", head)
			return
		}
		payload := make([]byte, len("firstsecond"))
		if _, err = io.ReadFull(reader, payload); err != nil {
			done <- err
			return
		}
		if string(payload) != "firstsecond" {
			done <- fmt.Errorf("payload = %q", payload)
			return
		}
		iv := bytes.Repeat([]byte{7}, 16)
		response := append([]byte("uninterpreted-prefix"), magic[:]...)
		response = append(response, 2, 'o', 'k', 16)
		response = append(response, iv...)
		plain := []byte("reply-one/reply-two")
		enc := make([]byte, len(plain))
		cipher.NewCTR(block, iv).XORKeyStream(enc, plain)
		response = append(response, enc...)
		// Force arbitrary boundaries inside MAGIC/token/IV/ciphertext.
		for _, b := range response {
			if _, err = peer.Write([]byte{b}); err != nil {
				done <- err
				return
			}
		}
		done <- nil
	}()
	base, err := net.DialTimeout("tcp", listener.Addr().String(), 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer base.Close()
	base.SetDeadline(time.Now().Add(5 * time.Second))
	stream, err := NewStreamCipher(password + ":ignored-key2")
	if err != nil {
		t.Fatal(err)
	}
	client := stream.ClientConn(base, M.ParseSocksaddr("example.test:443"), sess, []byte(token)).(*conn)
	if client.ReaderReplaceable() || client.WriterReplaceable() {
		t.Fatal("CTR state must never be bypassed")
	}
	for _, chunk := range []string{"first", "second"} {
		if n, err := client.Write([]byte(chunk)); err != nil || n != len(chunk) {
			t.Fatalf("write %d: %v", n, err)
		}
	}
	// Small reads exercise plaintext pending-buffer and continuous CTR state.
	var got []byte
	for len(got) < len("reply-one/reply-two") {
		b := make([]byte, 3)
		n, err := client.Read(b)
		if err != nil {
			t.Fatal(err)
		}
		got = append(got, b[:n]...)
	}
	if string(got) != "reply-one/reply-two" {
		t.Fatalf("reply = %q", got)
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}
