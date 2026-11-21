package libcore

import (
	"errors"
	"fmt"
	"net"
	"os"
	"sync"
	"syscall"
	"time"

	"golang.org/x/sys/unix"
)

// androidProtectServer owns received SCM_RIGHTS duplicates, never the caller's
// socket. Success is the legacy one-byte reply. Failure is EOF, not a zero-byte
// status value: some sing ProtectPath callers check only reply length.
type androidProtectServer struct {
	listener    *net.UnixListener
	protect     func(int) error
	access      sync.Mutex
	closed      bool
	connections map[*net.UnixConn]bool
	wait        sync.WaitGroup
}

func serveAndroidProtect(path string, protect func(int) error) (*androidProtectServer, error) {
	if protect == nil {
		return nil, errors.New("socket protect callback unavailable")
	}
	address := &net.UnixAddr{Name: path, Net: "unix"}
	// Remove only our own abandoned socket, never an active listener or a file.
	if info, err := os.Lstat(path); err == nil {
		stat, ok := info.Sys().(*syscall.Stat_t)
		if info.Mode()&os.ModeSocket == 0 || !ok || stat.Uid != uint32(os.Getuid()) {
			return nil, errors.New("unsafe protect socket path")
		}
		conn, dialErr := net.DialTimeout("unix", path, 100*time.Millisecond)
		if dialErr == nil {
			_ = conn.Close()
			return nil, errors.New("protect socket already active")
		}
		if !errors.Is(dialErr, syscall.ECONNREFUSED) && !errors.Is(dialErr, os.ErrNotExist) {
			return nil, fmt.Errorf("check protect socket: %w", dialErr)
		}
		if err = os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
			return nil, err
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	listener, err := net.ListenUnix("unix", address)
	if err != nil {
		return nil, err
	}
	listener.SetUnlinkOnClose(true)
	if err = os.Chmod(path, 0600); err != nil {
		_ = listener.Close()
		return nil, err
	}
	server := &androidProtectServer{listener: listener, protect: protect, connections: make(map[*net.UnixConn]bool)}
	server.wait.Add(1)
	go server.accept()
	return server, nil
}
func (s *androidProtectServer) accept() {
	defer s.wait.Done()
	for {
		conn, err := s.listener.AcceptUnix()
		if err != nil {
			return
		}
		s.access.Lock()
		if s.closed {
			s.access.Unlock()
			_ = conn.Close()
			return
		}
		s.connections[conn] = true
		s.wait.Add(1)
		s.access.Unlock()
		go s.handle(conn)
	}
}
func (s *androidProtectServer) handle(conn *net.UnixConn) {
	defer s.wait.Done()
	defer func() {
		// A JNI callback panic must not crash the background process or send success.
		_ = recover()
		_ = conn.Close()
		s.access.Lock()
		delete(s.connections, conn)
		s.access.Unlock()
	}()
	_ = conn.SetDeadline(time.Now().Add(2 * time.Second))
	payload := make([]byte, 1)
	oob := make([]byte, unix.CmsgSpace(16*4))
	n, oobn, flags, _, err := conn.ReadMsgUnix(payload, oob)
	if err != nil {
		return
	}
	messages, err := unix.ParseSocketControlMessage(oob[:oobn])
	if err != nil {
		return
	}
	descriptors := []int{}
	defer func() {
		for _, fd := range descriptors {
			_ = unix.Close(fd)
		}
	}()
	valid := true
	for _, message := range messages {
		rights, parseErr := unix.ParseUnixRights(&message)
		if parseErr != nil {
			valid = false
			continue
		}
		descriptors = append(descriptors, rights...)
	}
	if !valid || n != 1 || flags&unix.MSG_CTRUNC != 0 || len(descriptors) != 1 {
		return
	}
	fd := descriptors[0]
	unix.CloseOnExec(fd)
	var stat unix.Stat_t
	if err = unix.Fstat(fd, &stat); err != nil || stat.Mode&unix.S_IFMT != unix.S_IFSOCK {
		return
	}
	if err = s.protect(fd); err != nil {
		return
	}
	_, _ = conn.Write([]byte{1})
}
func (s *androidProtectServer) Close() error {
	s.access.Lock()
	if s.closed {
		s.access.Unlock()
		s.wait.Wait()
		return nil
	}
	s.closed = true
	err := s.listener.Close()
	for conn := range s.connections {
		_ = conn.Close()
	}
	s.access.Unlock()
	s.wait.Wait()
	return err
}
