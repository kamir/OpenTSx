package metadata

import (
	"context"
	"encoding/json"
	"errors"
	"math"
	"reflect"
	"strconv"
	"testing"

	"github.com/KafScale/platform/pkg/protocol"
	"github.com/twmb/franz-go/pkg/kmsg"
	pb "go.etcd.io/etcd/api/v3/etcdserverpb"
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

const offsetTestKey = "/kafscale/topics/orders/partitions/0/next_offset"

type offsetTestKV struct {
	clientv3.KV
	value        string
	revision     int64
	writes       int
	getError     error
	txnError     error
	beforeCommit func()
}

func (kv *offsetTestKV) Get(ctx context.Context, key string, _ ...clientv3.OpOption) (*clientv3.GetResponse, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if key != offsetTestKey {
		return nil, errors.New("unexpected offset key")
	}
	if kv.getError != nil {
		return nil, kv.getError
	}
	response := &clientv3.GetResponse{}
	if kv.revision != 0 {
		response.Kvs = []*mvccpb.KeyValue{{Key: []byte(key), Value: []byte(kv.value), ModRevision: kv.revision}}
		response.Count = 1
	}
	return response, nil
}

func (kv *offsetTestKV) Put(ctx context.Context, key string, value string, _ ...clientv3.OpOption) (*clientv3.PutResponse, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if key != offsetTestKey {
		return nil, errors.New("unexpected offset key")
	}
	kv.value = value
	kv.revision++
	kv.writes++
	return &clientv3.PutResponse{}, nil
}

func (kv *offsetTestKV) Txn(ctx context.Context) clientv3.Txn {
	return &offsetTestTxn{kv: kv, ctx: ctx}
}

type offsetTestTxn struct {
	clientv3.Txn
	kv          *offsetTestKV
	ctx         context.Context
	comparisons []clientv3.Cmp
	operations  []clientv3.Op
}

func (txn *offsetTestTxn) If(comparisons ...clientv3.Cmp) clientv3.Txn {
	txn.comparisons = comparisons
	return txn
}

func (txn *offsetTestTxn) Then(operations ...clientv3.Op) clientv3.Txn {
	txn.operations = operations
	return txn
}

func (txn *offsetTestTxn) Commit() (*clientv3.TxnResponse, error) {
	if err := txn.ctx.Err(); err != nil {
		return nil, err
	}
	if txn.kv.txnError != nil {
		return nil, txn.kv.txnError
	}
	if hook := txn.kv.beforeCommit; hook != nil {
		txn.kv.beforeCommit = nil
		hook()
	}
	success := true
	for _, comparison := range txn.comparisons {
		value := pb.Compare(comparison)
		if value.Result != pb.Compare_EQUAL {
			panic("unexpected offset comparison")
		}
		switch value.Target {
		case pb.Compare_VERSION:
			version := int64(0)
			if txn.kv.revision != 0 {
				version = 1
			}
			success = success && version == value.GetVersion()
		case pb.Compare_MOD:
			success = success && txn.kv.revision == value.GetModRevision()
		default:
			panic("unexpected offset comparison target")
		}
	}
	if success {
		for _, operation := range txn.operations {
			if !operation.IsPut() {
				panic("unexpected offset operation")
			}
			if _, err := txn.kv.Put(txn.ctx, string(operation.KeyBytes()), string(operation.ValueBytes())); err != nil {
				return nil, err
			}
		}
	}
	return &clientv3.TxnResponse{Succeeded: success}, nil
}

func offsetTestStore(kv *offsetTestKV) *EtcdStore {
	return &EtcdStore{client: &clientv3.Client{KV: kv}, available: 1, metadata: NewInMemoryStore(ClusterMetadata{Topics: []protocol.MetadataTopic{{Topic: kmsg.StringPtr("orders"), Partitions: []protocol.MetadataPartition{{Partition: 0}}}}})}
}

func TestOpenTSxMetadataOffsetsNeverRegress(t *testing.T) {
	for _, values := range [][]int64{{4, 1}, {8, 9, 9, 8}, {0, 0}, {math.MaxInt64 - 1, 0}} {
		t.Run(strconv.FormatInt(values[0], 10), func(t *testing.T) {
			kv := &offsetTestKV{}
			store := offsetTestStore(kv)
			maximum := int64(0)
			for _, value := range values {
				if err := store.UpdateOffsets(context.Background(), "orders", 0, value); err != nil {
					t.Fatal(err)
				}
				if value+1 > maximum {
					maximum = value + 1
				}
				actual, err := store.NextOffset(context.Background(), "orders", 0)
				if err != nil {
					t.Fatal(err)
				}
				if actual != maximum {
					t.Fatalf("offset regressed: got %d, want %d", actual, maximum)
				}
			}
		})
	}
}

func TestOpenTSxMetadataOffsetsRetryConcurrentRevisionChanges(t *testing.T) {
	for _, initial := range []string{"", "2"} {
		for _, competing := range []string{"5", "12"} {
			t.Run(initial+"/"+competing, func(t *testing.T) {
				kv := &offsetTestKV{value: initial}
				if initial != "" {
					kv.revision = 1
				}
				kv.beforeCommit = func() {
					if _, err := kv.Put(context.Background(), offsetTestKey, competing); err != nil {
						t.Fatal(err)
					}
				}
				store := offsetTestStore(kv)
				if err := store.UpdateOffsets(context.Background(), "orders", 0, 8); err != nil {
					t.Fatal(err)
				}
				expected := "9"
				if competing == "12" {
					expected = competing
				}
				if kv.value != expected {
					t.Fatalf("concurrent offset was overwritten: got %s, want %s", kv.value, expected)
				}
				if kv.beforeCommit != nil {
					t.Fatal("the conditional update was not executed")
				}
			})
		}
	}
}

func TestOpenTSxMetadataOffsetsPreserveBlankAndWhitespaceValues(t *testing.T) {
	for _, value := range []string{"", " 2 \n"} {
		kv := &offsetTestKV{value: value, revision: 1}
		store := offsetTestStore(kv)
		if err := store.UpdateOffsets(context.Background(), "orders", 0, 4); err != nil {
			t.Fatal(err)
		}
		actual, err := store.NextOffset(context.Background(), "orders", 0)
		if err != nil {
			t.Fatal(err)
		}
		if actual != 5 {
			t.Fatalf("offset %q did not advance to 5: got %d", value, actual)
		}
	}
}

func TestOpenTSxMetadataOffsetsRejectInvalidInputAndCorruptState(t *testing.T) {
	for _, last := range []int64{-1, math.MinInt64, math.MaxInt64} {
		kv := &offsetTestKV{}
		if err := offsetTestStore(kv).UpdateOffsets(context.Background(), "orders", 0, last); err == nil {
			t.Fatalf("accepted invalid last offset %d", last)
		}
		if kv.writes != 0 {
			t.Fatal("invalid offset was persisted")
		}
	}
	for _, value := range []string{"broken", "-1", "9223372036854775808"} {
		kv := &offsetTestKV{value: value, revision: 1}
		if err := offsetTestStore(kv).UpdateOffsets(context.Background(), "orders", 0, 8); err == nil {
			t.Fatalf("replaced corrupt committed offset %q", value)
		}
		if kv.writes != 0 {
			t.Fatal("corrupt committed offset was overwritten")
		}
	}
}

func TestOpenTSxMetadataOffsetsPropagateStorageFailureAndCancellation(t *testing.T) {
	failure := errors.New("storage unavailable")
	for _, operation := range []string{"get", "transaction", "cancelled"} {
		t.Run(operation, func(t *testing.T) {
			kv := &offsetTestKV{}
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			wanted := failure
			switch operation {
			case "get":
				kv.getError = failure
			case "transaction":
				kv.txnError = failure
			case "cancelled":
				cancel()
				wanted = context.Canceled
			}
			store := offsetTestStore(kv)
			if err := store.UpdateOffsets(ctx, "orders", 0, 8); !errors.Is(err, wanted) {
				t.Fatalf("got %v, want %v", err, wanted)
			}
			if kv.writes != 0 || store.Available() {
				t.Fatal("failed persistence was reported as available or wrote an offset")
			}
		})
	}
}
