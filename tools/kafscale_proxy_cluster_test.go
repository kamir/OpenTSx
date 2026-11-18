package main

import (
	"errors"
	"io"
	"testing"
)

func TestOpenTSxClusterAllocatesDistinctPrivatePorts(t *testing.T) {
	for _, fixture := range []struct {
		name       string
		public     string
		candidates []string
		expected   [3]string
	}{
		{"public-port", "127.0.0.1:39092", []string{"127.0.0.1:39092", "127.0.0.1:31001", "127.0.0.1:31002", "127.0.0.1:31003"}, [3]string{"127.0.0.1:31001", "127.0.0.1:31002", "127.0.0.1:31003"}},
		{"repeated-private-port", "127.0.0.1:39092", []string{"127.0.0.1:31001", "127.0.0.1:31001", "127.0.0.1:31001", "127.0.0.1:31002", "127.0.0.1:31003"}, [3]string{"127.0.0.1:31001", "127.0.0.1:31002", "127.0.0.1:31003"}},
		{"port-independent-of-host", "[::1]:039092", []string{"127.0.0.1:39092", "127.0.0.1:31001", "0.0.0.0:31001", "127.0.0.1:31002", "127.0.0.1:31003"}, [3]string{"127.0.0.1:31001", "127.0.0.1:31002", "127.0.0.1:31003"}},
		{"already-distinct", "127.0.0.1:39092", []string{"127.0.0.1:31001", "127.0.0.1:31002", "127.0.0.1:31003"}, [3]string{"127.0.0.1:31001", "127.0.0.1:31002", "127.0.0.1:31003"}},
	} {
		t.Run(fixture.name, func(t *testing.T) {
			calls := 0
			actual, err := localAddresses(fixture.public, func() (string, error) {
				if calls == len(fixture.candidates) {
					return "", io.EOF
				}
				address := fixture.candidates[calls]
				calls++
				return address, nil
			})
			if err != nil {
				t.Fatal(err)
			}
			if actual != fixture.expected {
				t.Fatalf("private ports collide: got %v, want %v", actual, fixture.expected)
			}
			if calls != len(fixture.candidates) {
				t.Fatalf("allocated with %d candidate calls, want %d", calls, len(fixture.candidates))
			}
		})
	}
}

func TestOpenTSxClusterAllocationFailsClosed(t *testing.T) {
	failure := errors.New("listener allocation failed")
	for _, fixture := range []struct {
		name    string
		address string
		err     error
	}{
		{"allocation-error", "", failure},
		{"invalid-address", "invalid", nil},
		{"invalid-port", "127.0.0.1:65536", nil},
		{"unassigned-port", "127.0.0.1:0", nil},
	} {
		t.Run(fixture.name, func(t *testing.T) {
			actual, err := localAddresses("127.0.0.1:39092", func() (string, error) { return fixture.address, fixture.err })
			if err == nil || actual != [3]string{} {
				t.Fatalf("invalid allocation succeeded: addresses %v, error %v", actual, err)
			}
			if fixture.err != nil && !errors.Is(err, fixture.err) {
				t.Fatalf("allocation error changed: got %v", err)
			}
		})
	}
}

func TestOpenTSxClusterAllocationStopsAfterRepeatedCollisions(t *testing.T) {
	calls := 0
	actual, err := localAddresses("127.0.0.1:39092", func() (string, error) { calls++; return "127.0.0.1:39092", nil })
	if err == nil || actual != [3]string{} || calls != 64 {
		t.Fatalf("collision retries were not bounded: addresses %v, error %v, calls %d", actual, err, calls)
	}
}

func TestOpenTSxClusterRejectsInvalidPublicListener(t *testing.T) {
	for _, public := range []string{"invalid", "127.0.0.1:65536", "127.0.0.1:0"} {
		t.Run(public, func(t *testing.T) {
			calls := 0
			actual, err := localAddresses(public, func() (string, error) { calls++; return "127.0.0.1:31001", nil })
			if err == nil || actual != [3]string{} || calls != 0 {
				t.Fatalf("invalid public listener reached private allocation: addresses %v, error %v, calls %d", actual, err, calls)
			}
		})
	}
}
