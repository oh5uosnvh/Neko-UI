package libcore

import (
	"fmt"
	"golang.org/x/sys/unix"
)

func sendFdToProtect(fd int, path string) error {
	socketFd, err := unix.Socket(unix.AF_UNIX, unix.SOCK_STREAM|unix.SOCK_CLOEXEC, 0)
	if err != nil {
		return fmt.Errorf("failed to create unix socket: %w", err)
	}
	defer unix.Close(socketFd)
	timeout := unix.Timeval{Usec: 100 * 1000}
	if err = unix.SetsockoptTimeval(socketFd, unix.SOL_SOCKET, unix.SO_RCVTIMEO, &timeout); err != nil {
		return err
	}
	if err = unix.SetsockoptTimeval(socketFd, unix.SOL_SOCKET, unix.SO_SNDTIMEO, &timeout); err != nil {
		return err
	}
	if err = unix.Connect(socketFd, &unix.SockaddrUnix{Name: path}); err != nil {
		return fmt.Errorf("failed to connect protect service: %w", err)
	}
	if err = unix.Sendmsg(socketFd, []byte{1}, unix.UnixRights(fd), nil, 0); err != nil {
		return fmt.Errorf("failed to send socket for protection: %w", err)
	}
	reply := []byte{0}
	n, err := unix.Read(socketFd, reply)
	if err != nil {
		return fmt.Errorf("failed to receive protection result: %w", err)
	}
	if n != 1 {
		return fmt.Errorf("protect socket closed without success")
	}
	if reply[0] != 1 {
		return fmt.Errorf("socket protection was rejected")
	}
	return nil
}
