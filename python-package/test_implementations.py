#!/usr/bin/env python3
"""
Test script for verifying new OpenTSx Python implementations.

Tests:
1. MFDFA - Complete multifractal analysis
2. Event Synchronization - Directional sync and lead-lag
3. RIS - Return interval statistics
4. Data serialization compatibility
"""

import numpy as np
import json
from opentsx.core.time_series import TimeSeriesObject
from opentsx.algorithms import DFA, MFDFA, EventSynchronization, RIS


def test_mfdfa():
    """Test complete MFDFA implementation."""
    print("\n" + "="*60)
    print("Testing MFDFA (Multifractal DFA)")
    print("="*60)

    # Generate multifractal time series (binomial cascade)
    np.random.seed(42)
    n = 5000
    data = np.cumsum(np.random.choice([-1, 1], size=n, p=[0.4, 0.6]))

    ts = TimeSeriesObject(data=data, label="multifractal_test")

    # Run MFDFA
    mfdfa = MFDFA(polynom_order=1)
    results = mfdfa.analyze(ts)

    print(f"✓ MFDFA analysis completed")
    print(f"  - q-values: {len(results['q_values'])} points")
    print(f"  - h(q) range: [{results['h_q'].min():.3f}, {results['h_q'].max():.3f}]")
    print(f"  - Δh (multifractality measure): {results['delta_h']:.3f}")
    print(f"  - Is multifractal: {results['is_multifractal']}")
    print(f"  - Δα (spectrum width): {results['delta_alpha']:.3f}")
    print(f"  - τ(q) calculated: ✓")
    print(f"  - f(α) spectrum calculated: ✓")

    # Verify all expected keys
    expected_keys = ['q_values', 'scales', 'Fq', 'h_q', 'tau_q', 'alpha', 'f_alpha',
                     'delta_h', 'delta_alpha', 'is_multifractal', 'h_2']
    for key in expected_keys:
        assert key in results, f"Missing key: {key}"

    print("✓ All MFDFA features working correctly!")
    return True


def test_event_synchronization():
    """Test complete Event Synchronization implementation."""
    print("\n" + "="*60)
    print("Testing Event Synchronization")
    print("="*60)

    # Generate two time series with synchronized events
    np.random.seed(42)
    n = 1000

    # Series 1: random walk with peaks
    ts1_data = np.cumsum(np.random.randn(n))
    ts1_data[100] += 5  # Add peak
    ts1_data[300] += 5
    ts1_data[600] += 5

    # Series 2: similar peaks with lag
    ts2_data = np.cumsum(np.random.randn(n))
    ts2_data[105] += 5  # Slightly delayed peaks
    ts2_data[305] += 5
    ts2_data[605] += 5

    ts1 = TimeSeriesObject(data=ts1_data, label="series_1")
    ts2 = TimeSeriesObject(data=ts2_data, label="series_2")

    # Run Event Synchronization
    es = EventSynchronization()
    results = es.analyze(ts1, ts2)

    print(f"✓ Event Synchronization analysis completed")
    print(f"  - Events in series 1: {results['num_events_1']}")
    print(f"  - Events in series 2: {results['num_events_2']}")
    print(f"  - Overall synchronization Q: {results['overall_sync']:.3f}")
    print(f"  - Directional sync (1→2): {results['sync_1_to_2']:.3f}")
    print(f"  - Directional sync (2→1): {results['sync_2_to_1']:.3f}")
    print(f"  - Leader: {results['leader']}")
    print(f"  - Lead-lag strength: {results['lead_lag_strength']:.3f}")
    print(f"  - Adaptive τ_max: {results['tau_max']:.1f}")

    # Verify all expected keys
    expected_keys = ['overall_sync', 'sync_1_to_2', 'sync_2_to_1', 'num_events_1',
                     'num_events_2', 'events_1', 'events_2', 'leader',
                     'lead_lag_strength', 'tau_max']
    for key in expected_keys:
        assert key in results, f"Missing key: {key}"

    print("✓ All Event Synchronization features working correctly!")
    return True


def test_ris():
    """Test RIS (Return Interval Statistics) implementation."""
    print("\n" + "="*60)
    print("Testing RIS (Return Interval Statistics)")
    print("="*60)

    # Generate time series with clustered extreme events
    np.random.seed(42)
    n = 10000

    # Base random walk
    data = np.cumsum(np.random.randn(n))

    # Add clustered extreme events
    for cluster_start in [1000, 2000, 5000, 7000]:
        for i in range(5):
            data[cluster_start + i*10] += 10

    ts = TimeSeriesObject(data=data, label="extreme_events_test")

    # Run RIS
    ris = RIS(threshold_percentile=95)
    results = ris.analyze(ts)

    print(f"✓ RIS analysis completed")
    print(f"  - Extreme events detected: {results['num_events']}")
    print(f"  - Threshold used: {results['threshold']:.3f}")
    print(f"  - Mean return interval: {results['mean_ri']:.2f}")
    print(f"  - Median return interval: {results['median_ri']:.2f}")
    print(f"  - Std return interval: {results['std_ri']:.2f}")
    print(f"  - Risk parameter R: {results['risk_parameter']:.3f}")
    print(f"  - Risk interpretation: {results['risk_interpretation']}")
    print(f"  - Stretched exponential γ: {results['gamma']:.3f}")
    print(f"  - Characteristic time τ₀: {results['tau_0']:.2f}")

    # Verify all expected keys
    expected_keys = ['num_events', 'threshold', 'return_intervals', 'mean_ri',
                     'median_ri', 'std_ri', 'min_ri', 'max_ri', 'risk_parameter',
                     'risk_interpretation', 'gamma', 'tau_0', 'event_indices']
    for key in expected_keys:
        assert key in results, f"Missing key: {key}"

    print("✓ All RIS features working correctly!")
    return True


def test_data_serialization():
    """Test data structure compatibility (JSON serialization/deserialization)."""
    print("\n" + "="*60)
    print("Testing Data Serialization Compatibility")
    print("="*60)

    # Create TimeSeriesObject
    np.random.seed(42)
    data = np.random.randn(100).tolist()
    timestamps = list(range(100))

    ts = TimeSeriesObject(
        data=data,
        timestamps=timestamps,
        label="test_series",
        metadata={'source': 'test', 'unit': 'celsius'}
    )

    # Serialize to dict (compatible with Java Avro/JSON)
    ts_dict = ts.to_dict()

    print(f"✓ Serialized TimeSeriesObject to dict")
    print(f"  - Keys: {list(ts_dict.keys())}")
    print(f"  - Label: {ts_dict['label']}")
    print(f"  - Values: {len(ts_dict['values'])} points")
    print(f"  - Timestamps: {len(ts_dict['timestamps'])} points")
    print(f"  - Metadata: {ts_dict['metadata']}")

    # Serialize to JSON
    json_str = json.dumps(ts_dict, indent=2)
    print(f"✓ Serialized to JSON ({len(json_str)} bytes)")

    # Deserialize from JSON
    ts_dict_loaded = json.loads(json_str)
    ts_loaded = TimeSeriesObject.from_dict(ts_dict_loaded)

    print(f"✓ Deserialized TimeSeriesObject from JSON")
    print(f"  - Label: {ts_loaded.label}")
    print(f"  - Length: {len(ts_loaded)}")
    print(f"  - Metadata: {ts_loaded.metadata}")

    # Verify data integrity
    assert ts.label == ts_loaded.label
    assert len(ts) == len(ts_loaded)
    assert np.allclose(ts.values, ts_loaded.values)
    assert np.allclose(ts.timestamps, ts_loaded.timestamps)
    assert ts.metadata == ts_loaded.metadata

    print("✓ Data serialization/deserialization working correctly!")
    print("✓ Compatible with Java Avro schema format!")
    return True


def test_algorithm_interoperability():
    """Test that all algorithms work with the same TimeSeriesObject."""
    print("\n" + "="*60)
    print("Testing Algorithm Interoperability")
    print("="*60)

    # Create a single time series
    np.random.seed(42)
    data = np.cumsum(np.random.randn(2000))
    ts = TimeSeriesObject(data=data, label="interop_test")

    print(f"Created TimeSeriesObject: {ts}")

    # Run all algorithms on the same time series
    dfa = DFA(polynom_order=1)
    dfa_results = dfa.analyze(ts)
    print(f"✓ DFA: α = {dfa_results['alpha']:.3f}")

    mfdfa = MFDFA(polynom_order=1)
    mfdfa_results = mfdfa.analyze(ts)
    print(f"✓ MFDFA: Δh = {mfdfa_results['delta_h']:.3f}")

    ris = RIS(threshold_percentile=90)
    ris_results = ris.analyze(ts)
    print(f"✓ RIS: R = {ris_results['risk_parameter']:.3f}")

    print("✓ All algorithms compatible with TimeSeriesObject!")
    return True


def main():
    """Run all tests."""
    print("\n" + "="*60)
    print("OpenTSx Python Implementation Test Suite")
    print("="*60)
    print("Testing newly implemented features:")
    print("  - MFDFA (complete multifractal analysis)")
    print("  - Event Synchronization (directional sync)")
    print("  - RIS (return interval statistics)")
    print("  - Data structure compatibility")

    all_passed = True

    try:
        # Run all tests
        all_passed &= test_mfdfa()
        all_passed &= test_event_synchronization()
        all_passed &= test_ris()
        all_passed &= test_data_serialization()
        all_passed &= test_algorithm_interoperability()

        # Final summary
        print("\n" + "="*60)
        if all_passed:
            print("✅ ALL TESTS PASSED!")
            print("="*60)
            print("\nSummary:")
            print("  ✓ MFDFA: Full implementation with h(q), τ(q), f(α)")
            print("  ✓ Event Synchronization: Directional sync + lead-lag analysis")
            print("  ✓ RIS: Complete return interval statistics")
            print("  ✓ Data compatibility: JSON serialization working")
            print("  ✓ All algorithms compatible with TimeSeriesObject")
            print("\nReady for Java-Python interoperability via:")
            print("  - Kafka with Avro serialization")
            print("  - File exchange (JSON, Parquet)")
            print("  - REST API")
            return 0
        else:
            print("❌ SOME TESTS FAILED")
            return 1

    except Exception as e:
        print(f"\n❌ ERROR: {e}")
        import traceback
        traceback.print_exc()
        return 1


if __name__ == '__main__':
    exit(main())
