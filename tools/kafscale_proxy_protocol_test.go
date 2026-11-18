package main

import (
	"bytes"
	"context"
	"fmt"
	"net"
	"reflect"
	"testing"

	"github.com/KafScale/platform/pkg/protocol"
	"github.com/twmb/franz-go/pkg/kmsg"
)

func checkOpenTSxProxyRequest(t *testing.T, header *protocol.RequestHeader, request kmsg.Request, payload []byte) {
	t.Helper()
	formatter := kmsg.NewRequestFormatter(kmsg.FormatterClientID(*header.ClientID))
	framed := formatter.AppendRequest(nil, request, header.CorrelationID)
	if !bytes.Equal(payload, framed[4:]) {
		t.Fatal("the re-encoded request contains an extra frame length or changes its payload")
	}
	var wire bytes.Buffer
	if err := protocol.WriteFrame(&wire, payload); err != nil {
		t.Fatal(err)
	}
	frame, err := protocol.ReadFrame(&wire)
	if err != nil {
		t.Fatal(err)
	}
	parsedHeader, parsedRequest, err := protocol.ParseRequest(frame.Payload)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(header, parsedHeader) || parsedRequest.Key() != request.Key() || parsedRequest.GetVersion() != request.GetVersion() || wire.Len() != 0 {
		t.Fatal("the backend cannot read the re-encoded request as exactly one unchanged Kafka frame")
	}
}

func TestOpenTSxProxyProduceRequestFrame(t *testing.T) {
	for _, version := range []int16{3, 8, 9} {
		t.Run(fmt.Sprint(version), func(t *testing.T) {
			request := kmsg.NewPtrProduceRequest()
			request.SetVersion(version)
			request.Acks, request.TimeoutMillis = 1, 1000
			topic := kmsg.NewProduceRequestTopic()
			topic.Topic = "orders"
			partition := kmsg.NewProduceRequestTopicPartition()
			partition.Partition, partition.Records = 2, []byte{1, 2, 3}
			topic.Partitions = append(topic.Partitions, partition)
			request.Topics = append(request.Topics, topic)
			client := "protocol-regression"
			header := &protocol.RequestHeader{APIKey: protocol.APIKeyProduce, APIVersion: version, CorrelationID: 7, ClientID: &client}
			checkOpenTSxProxyRequest(t, header, request, encodeProduceRequest(header, request))
		})
	}
}

func TestOpenTSxProxyFetchRequestFrame(t *testing.T) {
	for _, version := range []int16{11, 12, 13} {
		t.Run(fmt.Sprint(version), func(t *testing.T) {
			request := kmsg.NewPtrFetchRequest()
			request.SetVersion(version)
			request.MaxWaitMillis, request.MinBytes, request.MaxBytes = 100, 1, 4096
			topic := kmsg.NewFetchRequestTopic()
			topic.Topic, topic.TopicID = "orders", [16]byte{1}
			partition := kmsg.NewFetchRequestTopicPartition()
			partition.Partition, partition.FetchOffset, partition.PartitionMaxBytes = 2, 13, 4096
			topic.Partitions = append(topic.Partitions, partition)
			request.Topics = append(request.Topics, topic)
			client := "protocol-regression"
			header := &protocol.RequestHeader{APIKey: protocol.APIKeyFetch, APIVersion: version, CorrelationID: 9, ClientID: &client}
			checkOpenTSxProxyRequest(t, header, request, encodeFetchRequest(header, request))
		})
	}
}

func TestOpenTSxProxySyntheticPartitionDefaults(t *testing.T) {
	for _, version := range []int16{3, 8, 9} {
		t.Run("produce-"+fmt.Sprint(version), func(t *testing.T) {
			request := kmsg.NewPtrProduceRequest()
			request.Topics = []kmsg.ProduceRequestTopic{{Topic: "orders", Partitions: []kmsg.ProduceRequestTopicPartition{{Partition: 2}}}}
			actual := kmsg.NewPtrProduceResponse()
			actual.SetVersion(version)
			addErrorForAllPartitions(actual, request, protocol.REQUEST_TIMED_OUT)
			partition := kmsg.NewProduceResponseTopicPartition()
			partition.Partition, partition.ErrorCode, partition.BaseOffset = 2, protocol.REQUEST_TIMED_OUT, -1
			expected := kmsg.NewPtrProduceResponse()
			expected.SetVersion(version)
			expected.Topics = []kmsg.ProduceResponseTopic{{Topic: "orders", Partitions: []kmsg.ProduceResponseTopicPartition{partition}}}
			if !reflect.DeepEqual(actual, expected) || !bytes.Equal(protocol.EncodeResponse(7, version, actual), protocol.EncodeResponse(7, version, expected)) {
				t.Fatal("synthetic produce failure has invented leader, timestamp or offset fields")
			}
		})
	}
	for _, version := range []int16{11, 12, 13} {
		t.Run("fetch-"+fmt.Sprint(version), func(t *testing.T) {
			request := kmsg.NewPtrFetchRequest()
			request.Topics = []kmsg.FetchRequestTopic{{Topic: "orders", TopicID: [16]byte{1}, Partitions: []kmsg.FetchRequestTopicPartition{{Partition: 2}}}}
			actual := kmsg.NewPtrFetchResponse()
			actual.SetVersion(version)
			addFetchErrorForAllPartitions(actual, request, protocol.REQUEST_TIMED_OUT)
			partition := kmsg.NewFetchResponseTopicPartition()
			partition.Partition, partition.ErrorCode = 2, protocol.REQUEST_TIMED_OUT
			expected := kmsg.NewPtrFetchResponse()
			expected.SetVersion(version)
			expected.Topics = []kmsg.FetchResponseTopic{{Topic: "orders", TopicID: [16]byte{1}, Partitions: []kmsg.FetchResponseTopicPartition{partition}}}
			if !reflect.DeepEqual(actual, expected) || !bytes.Equal(protocol.EncodeResponse(9, version, actual), protocol.EncodeResponse(9, version, expected)) {
				t.Fatal("synthetic fetch failure has invented leader, epoch, replica or offset fields")
			}
		})
	}
}

func TestOpenTSxProxyFanOutKeepsHealthyBackendEligible(t *testing.T) {
	for _, kind := range []string{"produce", "fetch"} {
		t.Run(kind, func(t *testing.T) {
			client, backend := net.Pipe()
			t.Cleanup(func() { client.Close(); backend.Close() })
			pool := newConnPool(0)
			pool.Return("healthy-backend", client)
			tried := map[string]bool{"previous-failure": true}
			var request kmsg.Request
			var response kmsg.Response
			if kind == "produce" {
				value := kmsg.NewPtrProduceRequest()
				value.SetVersion(9)
				value.Acks = 1
				value.Topics = []kmsg.ProduceRequestTopic{{Topic: "orders", Partitions: []kmsg.ProduceRequestTopicPartition{{Partition: 2}}}}
				request = value
				answer := kmsg.NewPtrProduceResponse()
				partition := kmsg.NewProduceResponseTopicPartition()
				partition.Partition = 2
				answer.Topics = []kmsg.ProduceResponseTopic{{Topic: "orders", Partitions: []kmsg.ProduceResponseTopicPartition{partition}}}
				response = answer
			} else {
				value := kmsg.NewPtrFetchRequest()
				value.SetVersion(13)
				value.Topics = []kmsg.FetchRequestTopic{{TopicID: [16]byte{1}, Partitions: []kmsg.FetchRequestTopicPartition{{Partition: 2}}}}
				request = value
				answer := kmsg.NewPtrFetchResponse()
				partition := kmsg.NewFetchResponseTopicPartition()
				partition.Partition = 2
				answer.Topics = []kmsg.FetchResponseTopic{{TopicID: [16]byte{1}, Partitions: []kmsg.FetchResponseTopicPartition{partition}}}
				response = answer
			}
			clientID := "fan-out-regression"
			header := &protocol.RequestHeader{APIKey: request.Key(), APIVersion: request.GetVersion(), CorrelationID: 11, ClientID: &clientID}
			payload := kmsg.NewRequestFormatter(kmsg.FormatterClientID(clientID)).AppendRequest(nil, request, header.CorrelationID)[4:]
			finished := make(chan error, 1)
			go func() {
				defer backend.Close()
				frame, err := protocol.ReadFrame(backend)
				if err == nil && !bytes.Equal(frame.Payload, payload) {
					err = fmt.Errorf("the original single-group payload changed")
				}
				if err == nil {
					err = protocol.WriteFrame(backend, protocol.EncodeResponse(header.CorrelationID, header.APIVersion, response))
				}
				finished <- err
			}()
			p := &proxy{}
			if kind == "produce" {
				groups := map[string]*kmsg.ProduceRequest{"healthy-backend": request.(*kmsg.ProduceRequest)}
				results := p.fanOutProduce(context.Background(), header, groups, payload, tried, pool)
				if len(results) != 1 || results[0].err != nil || results[0].target != "healthy-backend" {
					t.Fatalf("the in-memory produce backend did not succeed: %+v", results)
				}
			} else {
				groups := map[string]*kmsg.FetchRequest{"healthy-backend": request.(*kmsg.FetchRequest)}
				results := p.fanOutFetch(context.Background(), header, groups, payload, tried, pool)
				if len(results) != 1 || results[0].err != nil || results[0].target != "healthy-backend" {
					t.Fatalf("the in-memory fetch backend did not succeed: %+v", results)
				}
			}
			if err := <-finished; err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(tried, map[string]bool{"previous-failure": true}) {
				t.Fatal("a healthy backend became excluded from another disjoint partition group")
			}
		})
	}
}
