package libcore

import (
	"crypto/x509"
	"log"
	"sync"
)

var rootCAMutex sync.RWMutex
var externalRootCA string

func updateRootCACerts(pem []byte) {
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(pem) {
		log.Println("failed to append certificates from pem")
		return
	}
	rootCAMutex.Lock()
	externalRootCA = string(pem)
	rootCAMutex.Unlock()
	log.Println("external ca.pem was loaded")
}
func initSystemRoots() { _, _ = x509.SystemCertPool() }
