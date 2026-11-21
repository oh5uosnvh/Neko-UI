package libcore

import (
	"errors"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/sys/unix"
)

func TestAndroidProtectIPCResultAndFDOwnership(t *testing.T) {
	for _, mode := range []string{"success", "rejected", "panic"} {
		t.Run(mode, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "protect")
			var duplicate atomic.Int64
			duplicate.Store(-1)
			server, err := serveAndroidProtect(path, func(fd int) error {
				duplicate.Store(int64(fd))
				if _, err := unix.GetsockoptInt(fd, unix.SOL_SOCKET, unix.SO_TYPE); err != nil {
					return err
				}
				if mode == "panic" {
					panic("fixture callback panic")
				}
				if mode == "rejected" {
					return errors.New("VpnService.protect false")
				}
				return nil
			})
			if err != nil {
				t.Fatal(err)
			}
			defer server.Close()
			fd, err := unix.Socket(unix.AF_INET, unix.SOCK_STREAM|unix.SOCK_CLOEXEC, 0)
			if err != nil {
				t.Fatal(err)
			}
			defer unix.Close(fd)
			err = sendFdToProtect(fd, path)
			if (err == nil) != (mode == "success") {
				t.Fatalf("mode=%s err=%v", mode, err)
			}
			if _, err := unix.GetsockoptInt(fd, unix.SOL_SOCKET, unix.SO_TYPE); err != nil {
				t.Fatalf("caller original closed: %v", err)
			}
			if err := server.Close(); err != nil {
				t.Fatal(err)
			}
			if duplicate.Load() < 0 {
				t.Fatal("SCM_RIGHTS did not reach provider")
			}
			var stat unix.Stat_t
			if err := unix.Fstat(int(duplicate.Load()), &stat); !errors.Is(err, unix.EBADF) {
				t.Fatalf("received duplicate leaked: %v", err)
			}
			if _, err := os.Lstat(path); !errors.Is(err, os.ErrNotExist) {
				t.Fatalf("socket path not removed: %v", err)
			}
		})
	}
}
func TestAndroidProtectIPCRejectsNonSuccessReplyAndTimeout(t *testing.T) {
	for _, mode := range []string{"zero", "unknown", "eof", "timeout"} {
		t.Run(mode, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "protect")
			listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
			if err != nil {
				t.Fatal(err)
			}
			defer listener.Close()
			release, done := make(chan struct{}), make(chan struct{})
			go func() {
				defer close(done)
				conn, err := listener.AcceptUnix()
				if err != nil {
					return
				}
				defer conn.Close()
				if mode == "eof" {
					return
				}
				data, oob := make([]byte, 1), make([]byte, unix.CmsgSpace(4))
				_, n, _, _, err := conn.ReadMsgUnix(data, oob)
				if err != nil {
					return
				}
				messages, _ := unix.ParseSocketControlMessage(oob[:n])
				for _, m := range messages {
					fds, _ := unix.ParseUnixRights(&m)
					for _, fd := range fds {
						_ = unix.Close(fd)
					}
				}
				switch mode {
				case "zero":
					_, _ = conn.Write([]byte{0})
				case "unknown":
					_, _ = conn.Write([]byte{2})
				case "timeout":
					<-release
				}
			}()
			fd, err := unix.Socket(unix.AF_INET, unix.SOCK_STREAM, 0)
			if err != nil {
				t.Fatal(err)
			}
			defer unix.Close(fd)
			start := time.Now()
			if err := sendFdToProtect(fd, path); err == nil {
				t.Fatal("failure reply accepted")
			}
			close(release)
			<-done
			if time.Since(start) > time.Second {
				t.Fatal("protection IPC not bounded")
			}
		})
	}
}
func TestAndroidProtectIPCRejectsMissingServiceAndNonSocket(t *testing.T) {
	fd, err := unix.Socket(unix.AF_INET, unix.SOCK_STREAM, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer unix.Close(fd)
	if err := sendFdToProtect(fd, filepath.Join(t.TempDir(), "missing")); err == nil || !strings.Contains(err.Error(), "connect") {
		t.Fatalf("missing service err=%v", err)
	}
	path := filepath.Join(t.TempDir(), "protect")
	var calls atomic.Int32
	server, err := serveAndroidProtect(path, func(int) error { calls.Add(1); return nil })
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	file, err := os.CreateTemp(t.TempDir(), "not-socket")
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	if err := sendFdToProtect(int(file.Fd()), path); err == nil {
		t.Fatal("non-socket protected")
	}
	if calls.Load() != 0 {
		t.Fatal("invalid fd reached callback")
	}
}
