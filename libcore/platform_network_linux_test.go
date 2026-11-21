//go:build linux

package libcore

import (
	"net"
	"os"
	"os/exec"
	"runtime"
	"syscall"
	"testing"
	"unsafe"

	"golang.org/x/sys/unix"
)

// A separate process denies AF_NETLINK on ALL Go runtime threads. Unlike a
// host-only happy-path test this fails if core/provider/monitor falls back to
// Go net.Interfaces/InterfaceByName. No root required; CI may restrict seccomp.
func TestAndroidNetworkProductionDeniedNetlink(t *testing.T) {
	const child = "NEKO_TEST_DENY_NETLINK"
	const snapshot = "NEKO_TEST_NETWORK_SNAPSHOT"
	if os.Getenv(child) != "1" {
		callback := &liveNetworkCallback{value: networkTestJSON("wlan0", 77, "192.0.2.2/24")}
		command := exec.Command(os.Args[0], "-test.run=^TestAndroidNetworkProductionDeniedNetlink$", "-test.v")
		command.Env = append(os.Environ(), child+"=1", snapshot+"="+callback.value)
		output, err := command.CombinedOutput()
		if err != nil {
			t.Fatalf("denied-netlink subprocess: %v\n%s", err, output)
		}
		t.Log(string(output))
		return
	}
	filter := []unix.SockFilter{
		{Code: unix.BPF_LD | unix.BPF_W | unix.BPF_ABS, K: 0}, // seccomp_data.nr
		{Code: unix.BPF_JMP | unix.BPF_JEQ | unix.BPF_K, K: uint32(unix.SYS_SOCKET), Jf: 3},
		{Code: unix.BPF_LD | unix.BPF_W | unix.BPF_ABS, K: 16}, // args[0] address family
		{Code: unix.BPF_JMP | unix.BPF_JEQ | unix.BPF_K, K: unix.AF_NETLINK, Jf: 1},
		{Code: unix.BPF_RET | unix.BPF_K, K: unix.SECCOMP_RET_ERRNO | uint32(syscall.EACCES)},
		{Code: unix.BPF_RET | unix.BPF_K, K: unix.SECCOMP_RET_ALLOW},
	}
	runtime.LockOSThread()
	if err := unix.Prctl(unix.PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0); err != nil {
		t.Fatalf("seccomp no_new_privs: %v", err)
	}
	program := unix.SockFprog{Len: uint16(len(filter)), Filter: &filter[0]}
	result, _, errno := unix.Syscall(unix.SYS_SECCOMP, unix.SECCOMP_SET_MODE_FILTER, unix.SECCOMP_FILTER_FLAG_TSYNC, uintptr(unsafe.Pointer(&program)))
	if errno != 0 || result != 0 {
		t.Fatalf("seccomp TSYNC unavailable (CI must support it): result=%d errno=%v", result, errno)
	}
	runtime.UnlockOSThread()
	// Negative control proves this subprocess actually rejects netlink.
	if _, err := net.Interfaces(); err == nil {
		t.Fatal("netlink denial did not take effect")
	}
	callback := &liveNetworkCallback{value: os.Getenv(snapshot)}
	if callback.value == "" {
		t.Fatal("parent platform snapshot missing")
	}
	runProductionNetworkLifecycle(t, callback, true)
	// UI/proxy bootstrap also succeeds without a VPN/protect socket, under the
	// exact same denied-netlink process. Use a fresh callback for clean counters.
	for _, bg := range []bool{false, true} {
		runProductionNetworkLifecycle(t, &liveNetworkCallback{value: callback.value, noVPN: true}, bg)
	}
}
