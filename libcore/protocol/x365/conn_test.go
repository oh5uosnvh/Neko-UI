package x365

import (
	"bytes"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"testing"
	"time"

	"github.com/gofrs/uuid/v5"
	M "github.com/sagernet/sing/common/metadata"
)

// Wire format (from 365VPN libgojni.so reverse engineering):
//
//	request:  [0:4] "X365" | [4] 0x01 | [5] cmd | [6:22] uuid16 |
//	          [22:24] port BE | [24] atyp (0x01 IPv4 / 0x02 domain / 0x03 IPv6) | [25:] addr
//	response: [0:4] "X365" | [4] consumed
//
// The address trailer is PORT-THEN-ADDRESS (vmess.AddressSerializer), NOT
// SOCKS order (atyp+addr+port). This test locks that in.
func TestRequestWireFormatDomain(t *testing.T) {
	userID := "5e33609f-b819-49c4-8521-6d7a232c2a20"
	key, err := uuid.FromString(userID)
	if err != nil {
		t.Fatal(err)
	}
	dest := M.ParseSocksaddr("www.google.com:443")

	var buf bytes.Buffer
	if err := WriteRequest(&buf, key, CommandTCP, dest, nil); err != nil {
		t.Fatal(err)
	}
	b := buf.Bytes()

	if len(b) < 25 {
		t.Fatalf("request too short: %d", len(b))
	}
	// magic + version + command
	if !bytes.Equal(b[0:4], []byte("X365")) {
		t.Fatalf("magic = %x, want 58333635", b[0:4])
	}
	if b[4] != 0x01 {
		t.Fatalf("version = %#x, want 0x01", b[4])
	}
	if b[5] != CommandTCP {
		t.Fatalf("command = %#x, want 0x01", b[5])
	}
	// uuid raw 16 bytes
	if !bytes.Equal(b[6:22], key[:]) {
		t.Fatalf("uuid mismatch")
	}
	// PORT FIRST, big endian, at offset 22
	port := uint16(b[22])<<8 | uint16(b[23])
	if port != 443 {
		t.Fatalf("port = %d, want 443 (port must precede atyp)", port)
	}
	// atyp at offset 24: domain = 0x02 (vmess encoding, NOT 0x03 SOCKS)
	if b[24] != 0x02 {
		t.Fatalf("atyp = %#x, want 0x02 (vmess domain)", b[24])
	}
	// domain length + "www.google.com"
	if b[25] != 14 || string(b[26:40]) != "www.google.com" {
		t.Fatalf("domain section wrong: %x", b[25:])
	}
}

func TestRequestWireFormatIPv4(t *testing.T) {
	key := [16]byte{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16}
	dest := M.ParseSocksaddr("1.1.1.1:8443")

	var buf bytes.Buffer
	if err := WriteRequest(&buf, key, CommandUDP, dest, nil); err != nil {
		t.Fatal(err)
	}
	b := buf.Bytes()

	if b[5] != CommandUDP {
		t.Fatalf("command = %#x, want 0x02", b[5])
	}
	port := uint16(b[22])<<8 | uint16(b[23])
	if port != 8443 {
		t.Fatalf("port = %d, want 8443", port)
	}
	if b[24] != 0x01 { // IPv4
		t.Fatalf("atyp = %#x, want 0x01", b[24])
	}
	if !bytes.Equal(b[25:29], net.IPv4(1, 1, 1, 1).To4()) {
		t.Fatalf("ipv4 mismatch: %v", b[25:29])
	}
}

func TestResponseValidation(t *testing.T) {
	// valid: X365 + 1 padding byte (consumed, not part of stream)
	r := bytes.NewReader([]byte{'X', '3', '6', '5', 0x00, 0xAA, 0xBB})
	if err := ReadResponse(r); err != nil {
		t.Fatalf("valid response rejected: %v", err)
	}
	// the 6th/7th bytes must remain readable
	rest := make([]byte, 2)
	n, err := r.Read(rest)
	if err != nil || n != 2 || rest[0] != 0xAA || rest[1] != 0xBB {
		t.Fatalf("stream shifted after response: %v %x", err, rest)
	}

	// invalid magic
	if err := ReadResponse(bytes.NewReader([]byte{'X', '3', '6', '4', 0x00})); err == nil {
		t.Fatal("invalid magic accepted")
	}
}

// net.Pipe is unbuffered: the server must consume the complete request AND
// payload before writing the response. The old test read an oversized header
// with Read (not ReadFull), then replied while the client was still writing.
func TestConnRoundTrip(t *testing.T) {
	key := [16]byte{9, 9, 9, 9}
	dest := M.ParseSocksaddr("example.com:80")
	server, client := net.Pipe()
	defer server.Close()
	defer client.Close()
	server.SetDeadline(time.Now().Add(3 * time.Second))
	client.SetDeadline(time.Now().Add(3 * time.Second))
	var expected bytes.Buffer
	if err := WriteRequest(&expected, key, CommandTCP, dest, []byte("payload")); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() {
		got := make([]byte, expected.Len())
		if _, err := io.ReadFull(server, got); err != nil {
			done <- err
			return
		}
		if !bytes.Equal(got, expected.Bytes()) {
			done <- fmt.Errorf("request = %x", got)
			return
		}
		_, err := server.Write([]byte{'X', '3', '6', '5', 1, 'H', 'I'})
		done <- err
	}()
	c := NewConn(client, key, CommandTCP, dest)
	if _, err := c.Write(nil); err != nil {
		t.Fatal(err)
	}
	if _, err := c.Write([]byte("payload")); err != nil {
		t.Fatal(err)
	}
	out := make([]byte, 2)
	if _, err := io.ReadFull(c, out); err != nil {
		t.Fatal(err)
	}
	if string(out) != "HI" {
		t.Fatalf("payload = %q", out)
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

// Exercise a header-only handshake, multiple UDP frames, address reporting,
// and rejection of empty datagrams after the handshake, without skipping.
func TestPacketConnRoundTrip(t *testing.T) {
	key := [16]byte{1, 2, 3, 4}
	dest := M.ParseSocksaddr("8.8.8.8:53")
	server, client := net.Pipe()
	defer server.Close()
	defer client.Close()
	server.SetDeadline(time.Now().Add(3 * time.Second))
	client.SetDeadline(time.Now().Add(3 * time.Second))
	var head bytes.Buffer
	if err := WriteRequest(&head, key, CommandUDP, dest, nil); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() {
		got := make([]byte, head.Len())
		if _, err := io.ReadFull(server, got); err != nil {
			done <- err
			return
		}
		if !bytes.Equal(got, head.Bytes()) {
			done <- fmt.Errorf("header = %x", got)
			return
		}
		for i, payload := range []string{"abc", "second"} {
			frame := make([]byte, 2+len(payload))
			if _, err := io.ReadFull(server, frame); err != nil {
				done <- err
				return
			}
			if int(frame[0])<<8|int(frame[1]) != len(payload) || string(frame[2:]) != payload {
				done <- fmt.Errorf("frame = %x", frame)
				return
			}
			response := append([]byte{0, byte(len(payload))}, []byte(payload)...)
			if i == 0 {
				response = append([]byte{'X', '3', '6', '5', 0}, response...)
			}
			if _, err := server.Write(response); err != nil {
				done <- err
				return
			}
		}
		done <- nil
	}()
	pc := &PacketConn{Conn: client, key: key, destination: dest}
	if _, err := pc.Write(nil); err != nil {
		t.Fatal(err)
	}
	for _, payload := range []string{"abc", "second"} {
		if n, err := pc.Write([]byte(payload)); err != nil || n != len(payload) {
			t.Fatalf("write %d: %v", n, err)
		}
		out := make([]byte, 64)
		n, addr, err := pc.ReadFrom(out)
		if err != nil || string(out[:n]) != payload || addr.String() != dest.String() {
			t.Fatalf("read %q %v: %v", out[:n], addr, err)
		}
	}
	if _, err := pc.Write(nil); err == nil {
		t.Fatal("post-handshake empty datagram accepted")
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

func TestPacketConnFrame(t *testing.T) {
	key := [16]byte{1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1}
	dest := M.ParseSocksaddr("8.8.8.8:53")

	server, client := net.Pipe()
	defer server.Close()
	defer client.Close()

	pc := &PacketConn{Conn: client, key: key, destination: dest}
	go func() {
		buf := make([]byte, 1024)
		_, _ = server.Read(buf)
		// first write = header + 2-byte length prefix + payload
		if !bytes.Equal(buf[0:4], []byte("X365")) {
			return
		}
		if buf[5] != CommandUDP {
			return
		}
		// find length prefix right after address: port(2)+atyp(1)+ipv4(4)
		trailerStart := 22
		length := int(buf[trailerStart+7])<<8 | int(buf[trailerStart+8])
		if length != 3 || string(buf[trailerStart+9:trailerStart+9+length]) != "abc" {
			server.Write([]byte("XX"))
			return
		}
		server.Write([]byte{'X', '3', '6', '5', 0x00, 0x00, 0x02, 'o', 'k'})
	}()

	if _, err := pc.Write([]byte("abc")); err != nil {
		t.Fatal(err)
	}
	out := make([]byte, 64)
	n, _, err := pc.ReadFrom(out)
	if err != nil {
		t.Fatal(err)
	}
	if string(out[:n]) != "ok" {
		t.Fatalf("packet = %q, want ok", out[:n])
	}
	_ = hexdump
}

// hex helpers used in debugging
func hexdump(b []byte) string { return hex.EncodeToString(b) }
