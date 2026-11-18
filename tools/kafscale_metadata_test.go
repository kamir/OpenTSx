package metadata

import (
	"context"
	"encoding/json"
	"errors"
	"reflect"
	"testing"

	"github.com/KafScale/platform/pkg/protocol"
	"github.com/twmb/franz-go/pkg/kmsg"
	"go.etcd.io/etcd/api/v3/mvccpb"
	clientv3 "go.etcd.io/etcd/client/v3"
)

type openTSxMetadataKV struct {
	clientv3.KV
	get func(context.Context, string, ...clientv3.OpOption) (*clientv3.GetResponse, error)
}

func (kv openTSxMetadataKV) Get(ctx context.Context, key string, options ...clientv3.OpOption) (*clientv3.GetResponse, error) {
	return kv.get(ctx, key, options...)
}

func TestOpenTSxMetadataRefreshesEveryRead(t *testing.T) {
	for _, selection := range []struct {
		name   string
		topics []string
	}{
		{name: "all-topics"},
		{name: "selected-topic", topics: []string{"orders"}},
		{name: "unknown-topic", topics: []string{"missing"}},
	} {
		t.Run(selection.name, func(t *testing.T) {
			ctx := context.Background()
			stale := ClusterMetadata{Topics: []protocol.MetadataTopic{{Topic: kmsg.StringPtr("stale")}}}
			var payload []byte
			gets := 0
			kv := openTSxMetadataKV{get: func(_ context.Context, key string, options ...clientv3.OpOption) (*clientv3.GetResponse, error) {
				gets++
				if key != snapshotKey() || len(options) != 0 {
					t.Fatal("metadata must read the exact durable snapshot with a linearizable Get")
				}
				return &clientv3.GetResponse{Kvs: []*mvccpb.KeyValue{{Key: []byte(key), Value: payload}}}, nil
			}}
			store := &EtcdStore{client: &clientv3.Client{KV: kv}, metadata: NewInMemoryStore(stale), available: 1}
			for index, partitions := range []int{3, 4} {
				topic := protocol.MetadataTopic{Topic: kmsg.StringPtr("orders")}
				for partition := 0; partition < partitions; partition++ {
					topic.Partitions = append(topic.Partitions, protocol.MetadataPartition{Partition: int32(partition), Leader: 7})
				}
				durable := ClusterMetadata{ControllerID: 7, Brokers: []protocol.MetadataBroker{{NodeID: 7, Host: "proxy", Port: 9092}},
					Topics: []protocol.MetadataTopic{topic}}
				var err error
				payload, err = json.Marshal(durable)
				if err != nil {
					t.Fatal(err)
				}
				expected, err := NewInMemoryStore(durable).Metadata(ctx, selection.topics)
				if err != nil {
					t.Fatal(err)
				}
				actual, err := store.Metadata(ctx, selection.topics)
				if err != nil {
					t.Fatal(err)
				}
				if !reflect.DeepEqual(expected, actual) || gets != index+1 {
					t.Fatalf("metadata did not replace the stale cache with the latest durable snapshot: expected %#v, got %#v, Gets %d", expected, actual, gets)
				}
			}
		})
	}
}

func TestOpenTSxMetadataReadPropagatesRefreshFailure(t *testing.T) {
	for _, failure := range []error{errors.New("durable snapshot unavailable"), context.Canceled} {
		t.Run(failure.Error(), func(t *testing.T) {
			gets := 0
			kv := openTSxMetadataKV{get: func(context.Context, string, ...clientv3.OpOption) (*clientv3.GetResponse, error) {
				gets++
				return nil, failure
			}}
			cached := ClusterMetadata{Topics: []protocol.MetadataTopic{{Topic: kmsg.StringPtr("cached")}}}
			store := &EtcdStore{client: &clientv3.Client{KV: kv}, metadata: NewInMemoryStore(cached), available: 1}
			actual, err := store.Metadata(context.Background(), nil)
			if !errors.Is(err, failure) || actual != nil || gets != 1 || store.Available() {
				t.Fatalf("metadata returned cached success after a durable read failure: metadata %#v, error %v, Gets %d, available %t", actual, err, gets, store.Available())
			}
		})
	}
}
