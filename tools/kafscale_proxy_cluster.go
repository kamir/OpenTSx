package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"net"
	"net/url"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"sync"
	"syscall"
	"time"

	"go.etcd.io/etcd/server/v3/embed"
)

func localAddress() (string, error) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", err
	}
	address := listener.Addr().String()
	return address, listener.Close()
}

func localAddresses(public string, allocate func() (string, error)) ([3]string, error) {
	var addresses [3]string
	_, value, err := net.SplitHostPort(public)
	if err != nil {
		return addresses, fmt.Errorf("invalid Proxy listener: %w", err)
	}
	publicPort, err := net.LookupPort("tcp", value)
	if err != nil {
		return addresses, fmt.Errorf("invalid Proxy listener port: %w", err)
	}
	if publicPort == 0 {
		return addresses, errors.New("Proxy listener port must be nonzero")
	}
	used := map[int]bool{publicPort: true}
	for index := range addresses {
		for attempt := 0; attempt < 64; attempt++ {
			address, err := allocate()
			if err != nil {
				return [3]string{}, err
			}
			_, value, err := net.SplitHostPort(address)
			if err != nil {
				return [3]string{}, fmt.Errorf("invalid private listener: %w", err)
			}
			port, err := net.LookupPort("tcp", value)
			if err != nil || port == 0 {
				return [3]string{}, fmt.Errorf("invalid private listener port %q", value)
			}
			if used[port] {
				continue
			}
			used[port] = true
			addresses[index] = address
			break
		}
		if addresses[index] == "" {
			return [3]string{}, errors.New("could not allocate distinct private listener ports")
		}
	}
	return addresses, nil
}

func launch(ctx context.Context, binary string, environment []string) (*exec.Cmd, error) {
	command := exec.CommandContext(ctx, binary)
	command.Env = environment
	command.Stdout = os.Stdout
	command.Stderr = os.Stderr
	if err := command.Start(); err != nil {
		return nil, err
	}
	return command, nil
}

func run() error {
	listen := flag.String("listen", "127.0.0.1:39092", "Proxy listener address")
	host := flag.String("advertised-host", "127.0.0.1", "Proxy advertised hostname")
	port := flag.Int("advertised-port", 39092, "Proxy advertised port")
	directory := flag.String("data-dir", "", "Dedicated etcd data directory")
	brokerBinary := flag.String("broker-binary", "", "Pinned KafScale broker executable")
	proxyBinary := flag.String("proxy-binary", "", "Pinned KafScale Proxy executable")
	flag.Parse()
	if flag.NArg() != 0 || *directory == "" || *brokerBinary == "" || *proxyBinary == "" || *host == "" || *port < 1 || *port > 65535 {
		return errors.New("data-dir, broker-binary, proxy-binary and a valid advertised endpoint are required")
	}
	if _, _, err := net.SplitHostPort(*listen); err != nil {
		return fmt.Errorf("invalid Proxy listener: %w", err)
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	addresses, err := localAddresses(*listen, localAddress)
	if err != nil {
		return err
	}
	client, peer, backend := addresses[0], addresses[1], addresses[2]
	clientURL, err := url.Parse("http://" + client)
	if err != nil {
		return err
	}
	peerURL, err := url.Parse("http://" + peer)
	if err != nil {
		return err
	}
	config := embed.NewConfig()
	config.Dir = filepath.Join(*directory, "etcd")
	config.Logger = "zap"
	config.LogLevel = "error"
	config.ListenClientUrls = []url.URL{*clientURL}
	config.AdvertiseClientUrls = config.ListenClientUrls
	config.ListenPeerUrls = []url.URL{*peerURL}
	config.AdvertisePeerUrls = config.ListenPeerUrls
	config.InitialCluster = config.InitialClusterFromName(config.Name)
	etcd, err := embed.StartEtcd(config)
	if err != nil {
		return fmt.Errorf("start isolated etcd: %w", err)
	}
	defer etcd.Close()
	select {
	case <-etcd.Server.ReadyNotify():
	case <-ctx.Done():
		return ctx.Err()
	case <-time.After(30 * time.Second):
		return errors.New("isolated etcd startup exceeded 30 seconds")
	}
	_, backendPort, err := net.SplitHostPort(backend)
	if err != nil {
		return err
	}
	broker, err := launch(ctx, *brokerBinary, []string{
		"KAFSCALE_USE_MEMORY_S3=1", "KAFSCALE_BROKER_ID=0", "KAFSCALE_BROKER_ADDR=" + backend,
		"KAFSCALE_BROKER_HOST=127.0.0.1", "KAFSCALE_BROKER_PORT=" + backendPort,
		"KAFSCALE_ETCD_ENDPOINTS=" + clientURL.String(), "KAFSCALE_METRICS_ADDR=127.0.0.1:0",
		"KAFSCALE_CONTROL_ADDR=127.0.0.1:0",
	})
	if err != nil {
		return fmt.Errorf("start private broker: %w", err)
	}
	exits := make(chan error, 2)
	var children sync.WaitGroup
	watch := func(command *exec.Cmd) {
		children.Add(1)
		go func() { defer children.Done(); exits <- command.Wait() }()
	}
	watch(broker)
	defer func() { cancel(); children.Wait() }()
	proxy, err := launch(ctx, *proxyBinary, []string{
		"KAFSCALE_PROXY_ADDR=" + *listen, "KAFSCALE_PROXY_HEALTH_ADDR=127.0.0.1:0",
		"KAFSCALE_PROXY_ADVERTISED_HOST=" + *host, "KAFSCALE_PROXY_ADVERTISED_PORT=" + strconv.Itoa(*port),
		"KAFSCALE_PROXY_BACKENDS=" + backend, "KAFSCALE_PROXY_ETCD_ENDPOINTS=" + clientURL.String(),
	})
	if err != nil {
		return fmt.Errorf("start Proxy: %w", err)
	}
	watch(proxy)
	select {
	case <-ctx.Done():
		return nil
	case err := <-etcd.Err():
		return fmt.Errorf("isolated etcd exited: %w", err)
	case err := <-exits:
		return fmt.Errorf("KafScale child exited: %v", err)
	}
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
