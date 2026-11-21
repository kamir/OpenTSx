use std::sync::OnceLock;
use wasmtime::{
    Config, Engine, ExternType, Instance, Module, ResourceLimiter, Store, StoreLimits,
    StoreLimitsBuilder, Trap, ValType, WasmFeatures,
};

const MODULE_BYTES: usize = 65_536;
const FUEL: u64 = 10_000;
const MEMORY_BYTES: usize = 65_536;
const STACK_BYTES: usize = 256 * 1_024;
const CONVERT: &[u8] = include_bytes!("../wasm/convert_unit.wat");
static MODULE: OnceLock<Result<Module, String>> = OnceLock::new();

fn engine() -> Result<Engine, String> {
    let mut config = Config::new();
    config
        .target(if cfg!(target_pointer_width = "32") {
            "pulley32"
        } else {
            "pulley64"
        })
        .map_err(|error| format!("WASM target configuration failed: {error}"))?;
    config
        .consume_fuel(true)
        .max_wasm_stack(STACK_BYTES)
        .wasm_features(WasmFeatures::all(), false)
        .wasm_features(WasmFeatures::FLOATS | WasmFeatures::MUTABLE_GLOBAL, true);
    Engine::new(&config).map_err(|error| format!("WASM engine configuration failed: {error}"))
}

fn compile(engine: &Engine, bytes: &[u8]) -> Result<Module, String> {
    if bytes.len() > MODULE_BYTES {
        return Err("WASM module byte limit exceeded".into());
    }
    let binary = wat::parse_bytes(bytes)
        .map_err(|error| format!("WASM module validation failed: {error}"))?;
    if binary.len() > MODULE_BYTES {
        return Err("WASM module byte limit exceeded".into());
    }
    let module = Module::from_binary(engine, &binary)
        .map_err(|error| format!("WASM module validation failed: {error}"))?;
    if module.imports().next().is_some() {
        return Err("WASM imports are forbidden".into());
    }
    let function = match module.get_export("convert") {
        Some(ExternType::Func(function)) => function,
        _ => return Err("WASM convert ABI is invalid".into()),
    };
    if function.params().count() != 3
        || function.params().any(|ty| !matches!(ty, ValType::F64))
        || function.results().count() != 1
        || function.results().any(|ty| !matches!(ty, ValType::F64))
    {
        return Err("WASM convert ABI is invalid".into());
    }
    Ok(module)
}

fn embedded() -> Result<&'static Module, String> {
    MODULE
        .get_or_init(|| compile(&engine()?, CONVERT))
        .as_ref()
        .map_err(Clone::clone)
}

struct Resources {
    limits: StoreLimits,
    memory_failed: bool,
}

impl ResourceLimiter for Resources {
    fn memory_growing(
        &mut self,
        current: usize,
        desired: usize,
        maximum: Option<usize>,
    ) -> wasmtime::Result<bool> {
        let result = self.limits.memory_growing(current, desired, maximum);
        if !matches!(result, Ok(true)) {
            self.memory_failed = true;
        }
        result
    }

    fn memory_grow_failed(&mut self, error: wasmtime::Error) -> wasmtime::Result<()> {
        self.memory_failed = true;
        self.limits.memory_grow_failed(error)
    }

    fn table_growing(
        &mut self,
        current: usize,
        desired: usize,
        maximum: Option<usize>,
    ) -> wasmtime::Result<bool> {
        self.limits.table_growing(current, desired, maximum)
    }

    fn table_grow_failed(&mut self, error: wasmtime::Error) -> wasmtime::Result<()> {
        self.limits.table_grow_failed(error)
    }

    fn instances(&self) -> usize {
        self.limits.instances()
    }
    fn tables(&self) -> usize {
        self.limits.tables()
    }
    fn memories(&self) -> usize {
        self.limits.memories()
    }
}

fn store(engine: &Engine) -> Result<Store<Resources>, String> {
    let resources = Resources {
        limits: StoreLimitsBuilder::new()
            .memory_size(MEMORY_BYTES)
            .table_elements(0)
            .instances(1)
            .memories(1)
            .tables(0)
            .trap_on_grow_failure(true)
            .build(),
        memory_failed: false,
    };
    let mut store = Store::new(engine, resources);
    store.limiter(|resources| resources);
    store
        .set_fuel(FUEL)
        .map_err(|error| format!("WASM fuel configuration failed: {error}"))?;
    Ok(store)
}

fn failure(store: &Store<Resources>, error: wasmtime::Error) -> String {
    if store.data().memory_failed {
        return "WASM memory limit exceeded".into();
    }
    match error.downcast_ref::<Trap>() {
        Some(Trap::OutOfFuel) => "WASM fuel exhausted".into(),
        Some(Trap::StackOverflow) => "WASM stack limit exceeded".into(),
        _ => format!("WASM execution failed: {error}"),
    }
}

fn execute(module: &Module, values: &mut [f64], factor: f64, offset: f64) -> Result<(), String> {
    if !factor.is_finite() || !offset.is_finite() {
        return Err("WASM conversion parameters must be finite".into());
    }
    let mut store = store(module.engine())?;
    let instance =
        Instance::new(&mut store, module, &[]).map_err(|error| failure(&store, error))?;
    let convert = instance
        .get_typed_func::<(f64, f64, f64), f64>(&mut store, "convert")
        .map_err(|_| "WASM convert ABI is invalid".to_string())?;
    for value in values {
        store
            .set_fuel(FUEL)
            .map_err(|error| format!("WASM fuel configuration failed: {error}"))?;
        let output = convert
            .call(&mut store, (*value, factor, offset))
            .map_err(|error| failure(&store, error))?;
        if output.is_infinite() {
            return Err("unit conversion overflow".into());
        }
        *value = output;
    }
    Ok(())
}

pub(crate) fn convert_values(values: &mut [f64], factor: f64, offset: f64) -> Result<(), String> {
    #[cfg(test)]
    if let Some(module) = OVERRIDE.with(|current| current.borrow().clone()) {
        return execute(&module, values, factor, offset);
    }
    execute(embedded()?, values, factor, offset)
}

#[cfg(test)]
thread_local! {
    static OVERRIDE: std::cell::RefCell<Option<Module>> = const { std::cell::RefCell::new(None) };
}

#[cfg(test)]
mod wasm_limits {
    use super::*;
    use crate::admission::{admit, Admitted};
    use crate::broker::{Broker, InputRecord, OutputRecord};
    use crate::model::{self, Episode, Provenance, SeriesKey, TimeEncoding};
    use crate::operators::{Limits, Pipeline};
    use crate::{runner, wire};
    use serde_json::json;
    use std::collections::BTreeMap;

    const SIGNATURE: &str = "(param f64 f64 f64) (result f64)";

    fn module(body: &str) -> Module {
        compile(embedded().unwrap().engine(), body.as_bytes()).unwrap()
    }

    fn guest(body: &str) -> String {
        format!("(module (func (export \"convert\") {SIGNATURE} {body}))")
    }

    fn error(source: &str) -> String {
        execute(&module(source), &mut [1.0], 2.0, 1.0).unwrap_err()
    }

    struct Reset(Option<Module>);

    impl Drop for Reset {
        fn drop(&mut self) {
            OVERRIDE.with(|current| current.replace(self.0.take()));
        }
    }

    fn with_guest<T>(source: &str, run: impl FnOnce() -> T) -> T {
        let reset = Reset(OVERRIDE.with(|current| current.replace(Some(module(source)))));
        let result = run();
        drop(reset);
        result
    }

    fn fragment() -> serde_json::Value {
        json!({"schema":"opentsx.dag.v2","dag":"conversion", "source":{"kind":"kafscale-broker","topics":["tsx.test.episodes.v2"],"series":[episode().series.series_id]},
            "nodes":[{"id":"decode","op":"decode.avro-soe"},
            {"id":"convert","op":"convert.unit","in":["decode"],"params":{"factor":2,"offset":1}}],
            "sinks":[{"name":"test-sensor","schema":"opentsx.episode.v2","from":"convert","topic":"tsx.test.clean.v2",
                "signal":{"system":"test","id":"test/sensor","grade":"clean"},"origin":"SYNTHETIC"}]})
    }

    fn pipeline() -> Pipeline {
        Pipeline::compile(
            &fragment(),
            &"a".repeat(64),
            123_456_789,
            Limits {
                max_series: 1,
                max_samples: 10,
                max_output_samples: 10,
                max_state_bytes: 16_384,
            },
        )
        .unwrap()
    }

    fn episode() -> Episode {
        let tags = BTreeMap::from([("sensor".into(), "one".into())]);
        let values = vec![1.0];
        Episode {
            episode_id: "input".into(),
            series: SeriesKey {
                metric: "test.raw".into(),
                series_id: model::series_id("test.raw", &tags).unwrap(),
                tags,
                unit: None,
            },
            t_start: 1_000_000,
            t_end: 2_000_000,
            count: 1,
            time_encoding: TimeEncoding::Regular,
            interval_micros: Some(1_000_000),
            time_deltas: None,
            summary: model::summary(&values),
            values,
            quality: None,
            segmentation: None,
            labels: BTreeMap::new(),
            bucket_id: None,
            revision: 0,
            provenance: Provenance {
                source: "fixture".into(),
                producer: "fixture".into(),
                created_at: 1_000_000,
                pipeline: None,
                parent_episode_id: None,
            },
        }
    }

    #[test]
    fn endless_export_exhausts_fuel() {
        assert_eq!(
            error(&guest("(loop br 0) unreachable")),
            "WASM fuel exhausted"
        );
    }

    #[test]
    fn endless_start_exhausts_fuel_before_instantiation() {
        let source = format!("(module (func $start (loop br 0)) (start $start) (func (export \"convert\") {SIGNATURE} f64.const 0))");
        assert_eq!(error(&source), "WASM fuel exhausted");
    }

    #[test]
    fn oversized_initial_memory_fails_the_task() {
        let source =
            format!("(module (memory 2) (func (export \"convert\") {SIGNATURE} f64.const 0))");
        assert_eq!(error(&source), "WASM memory limit exceeded");
    }

    #[test]
    fn denied_memory_growth_fails_instead_of_returning_success() {
        let source = format!("(module (memory 1) (func (export \"convert\") {SIGNATURE} i32.const 1 memory.grow drop f64.const 0))");
        assert_eq!(error(&source), "WASM memory limit exceeded");
    }

    #[test]
    fn all_imports_are_refused() {
        for (namespace, name) in [
            ("wasi_snapshot_preview1", "fd_write"),
            ("wasi", "sock_open"),
            ("env", "clock"),
            ("env", "random"),
        ] {
            let source = format!("(module (import \"{namespace}\" \"{name}\" (func)) (func (export \"convert\") {SIGNATURE} f64.const 0))");
            assert_eq!(
                compile(embedded().unwrap().engine(), source.as_bytes()).unwrap_err(),
                "WASM imports are forbidden"
            );
        }
    }

    #[test]
    fn malformed_oversized_and_wrong_abi_modules_are_refused() {
        let engine = embedded().unwrap().engine();
        assert!(compile(engine, b"not a module")
            .unwrap_err()
            .starts_with("WASM module validation failed:"));
        assert_eq!(
            compile(engine, &vec![0; MODULE_BYTES + 1]).unwrap_err(),
            "WASM module byte limit exceeded"
        );
        for source in [
            "(module)",
            "(module (func (export \"convert\") (param f64) (result f64) local.get 0))",
            "(module (func (export \"convert\") (param f64 f64 f64) (result i32) i32.const 0))",
        ] {
            assert_eq!(
                compile(engine, source.as_bytes()).unwrap_err(),
                "WASM convert ABI is invalid"
            );
        }
    }

    #[test]
    fn unsupported_guest_features_are_refused() {
        for source in [
            format!("(module (memory 1) (memory 1) (func (export \"convert\") {SIGNATURE} f64.const 0))"),
            format!("(module (memory i64 1) (func (export \"convert\") {SIGNATURE} f64.const 0))"),
            format!("(module (memory 1 1 shared) (func (export \"convert\") {SIGNATURE} f64.const 0))"),
            guest("v128.const i32x4 0 0 0 0 drop f64.const 0"),
        ] {
            assert!(compile(embedded().unwrap().engine(), source.as_bytes()).unwrap_err().starts_with("WASM module validation failed:"));
        }
    }

    #[test]
    fn tables_and_additional_instances_are_refused() {
        let source = format!(
            "(module (table 0 funcref) (func (export \"convert\") {SIGNATURE} f64.const 0))"
        );
        assert!(error(&source).starts_with("WASM execution failed:"));
        let module = embedded().unwrap();
        let mut store = store(module.engine()).unwrap();
        Instance::new(&mut store, module, &[]).unwrap();
        assert!(Instance::new(&mut store, module, &[]).is_err());
    }

    #[test]
    fn fuel_is_bounded_and_replenished_per_scalar_call() {
        let module = embedded().unwrap();
        let mut store = store(module.engine()).unwrap();
        assert_eq!(store.get_fuel().unwrap(), FUEL);
        let instance = Instance::new(&mut store, module, &[]).unwrap();
        let convert = instance
            .get_typed_func::<(f64, f64, f64), f64>(&mut store, "convert")
            .unwrap();
        assert_eq!(convert.call(&mut store, (1.0, 2.0, 1.0)).unwrap(), 3.0);
        assert!(store.get_fuel().unwrap() < FUEL);
        let mut values = vec![1.0; FUEL as usize + 1];
        convert_values(&mut values, 2.0, 1.0).unwrap();
        assert!(values.iter().all(|value| *value == 3.0));
    }

    #[test]
    fn guest_stack_is_bounded_by_the_configured_limit() {
        let engine = embedded().unwrap().engine();
        assert_eq!(engine.get_max_wasm_stack(), STACK_BYTES);
        let source = format!("(module (func $recurse (export \"convert\") {SIGNATURE} local.get 0 local.get 1 local.get 2 call $recurse f64.const 1 f64.add))");
        let module = module(&source);
        let mut store = store(engine).unwrap();
        let instance = Instance::new(&mut store, &module, &[]).unwrap();
        let convert = instance
            .get_typed_func::<(f64, f64, f64), f64>(&mut store, "convert")
            .unwrap();
        store.set_fuel(1_000_000).unwrap();
        let error = convert.call(&mut store, (1.0, 2.0, 1.0)).unwrap_err();
        assert_eq!(failure(&store, error), "WASM stack limit exceeded");
    }

    #[test]
    fn numeric_boundaries_preserve_separate_operations() {
        let mut values = [1.0 + f64::EPSILON, -0.0, f64::NAN];
        convert_values(&mut values, 1.0 - f64::EPSILON, -1.0).unwrap();
        assert_eq!(values[0].to_bits(), 0.0f64.to_bits());
        assert_eq!(values[1], -1.0);
        assert!(values[2].is_nan());
        let mut negative = [-0.0];
        convert_values(&mut negative, 1.0, -0.0).unwrap();
        assert_eq!(negative[0].to_bits(), (-0.0f64).to_bits());
        assert_eq!(
            convert_values(&mut [f64::MAX], 2.0, 0.0).unwrap_err(),
            "unit conversion overflow"
        );
        for value in [f64::NAN, f64::INFINITY, f64::NEG_INFINITY] {
            assert_eq!(
                convert_values(&mut [1.0], value, 0.0).unwrap_err(),
                "WASM conversion parameters must be finite"
            );
            assert_eq!(
                convert_values(&mut [1.0], 1.0, value).unwrap_err(),
                "WASM conversion parameters must be finite"
            );
        }
    }

    #[test]
    fn trapped_conversion_preserves_the_real_pipeline_state() {
        for (source, expected) in trap_sources() {
            let mut pipeline = pipeline();
            let before = pipeline.snapshot().unwrap();
            let result = with_guest(&source, || pipeline.process(&[episode()]));
            assert_eq!(result.unwrap_err(), expected);
            assert_eq!(pipeline.snapshot().unwrap(), before);
            assert!(pipeline.process(&[episode()]).is_ok());
        }
    }

    fn trap_sources() -> [(String, &'static str); 3] {
        [
            (guest("(loop br 0) unreachable"), "WASM fuel exhausted"),
            (format!("(module (memory 2) (func (export \"convert\") {SIGNATURE} f64.const 0))"), "WASM memory limit exceeded"),
            (format!("(module (memory 1) (func (export \"convert\") {SIGNATURE} i32.const 1 memory.grow drop f64.const 0))"), "WASM memory limit exceeded"),
        ]
    }

    fn admitted() -> Admitted {
        let fragment = fragment().to_string();
        let digest = format!("sha256:{}", "a".repeat(64));
        let assignment = json!({
            "schema":"opentsx.execution-assignment.v1","deployment":"test-deployment","generation":"1",
            "assignment":"test-assignment","attempt":"test-attempt","fence":"1","fragment":"test-fragment",
            "site":"local","node":"worker-1","runtime":"rust","catalog":"tsx-catalog/1",
            "artifactDigest":digest,"specHash":tsx_contracts::sha256(&fragment),"sourceDagText":fragment,
            "fragmentHash":tsx_contracts::sha256(&fragment),"fragmentText":fragment,"mode":"bounded",
            "inputs":[{"binding":"local","topic":"tsx.test.episodes.v2","partitionCount":"1",
                "positions":[{"partition":"0","from":"0","to":"1"}]}]
        });
        let request = json!({"schema":"opentsx.local-bounded-run.v1","assignmentText":assignment.to_string(),
            "bindings":{"local":{"bootstrap":"localhost:9092","inputs":["tsx.test.episodes.v2"],"outputs":["tsx.test.clean.v2"]}},
            "sink":{"name":"test-sensor","binding":"local","topic":"tsx.test.clean.v2","partitionCount":"1"},
            "createdAtMicros":"123456789",
            "limits":{"records":1,"payloadBytes":4096,"inputBytes":4096,"samples":10,
                "outputBytes":4096,"series":1,"stateBytes":16384}});
        admit(&request.to_string(), &digest).unwrap()
    }

    #[derive(Default)]
    struct MemoryBroker {
        reads: usize,
        published: usize,
    }

    impl Broker for MemoryBroker {
        fn read(&mut self, _: &Admitted) -> Result<Vec<InputRecord>, String> {
            self.reads += 1;
            let episode = episode();
            Ok(vec![InputRecord {
                partition: 0,
                offset: 0,
                key: episode.series.series_id.as_bytes().to_vec(),
                payload: wire::encode(&episode)?,
            }])
        }

        fn publish(&mut self, _: &Admitted, _: &[OutputRecord]) -> Result<(), String> {
            self.published += 1;
            Ok(())
        }
    }

    #[test]
    fn traps_do_not_publish_or_commit_a_checkpoint() {
        static NEXT_DIRECTORY: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        struct Directory(std::path::PathBuf);
        impl Drop for Directory {
            fn drop(&mut self) {
                std::fs::remove_dir_all(&self.0).unwrap();
            }
        }
        for (source, expected) in trap_sources() {
            let directory = Directory(std::env::temp_dir().join(format!(
                "opentsx-wasm-{}-{}",
                std::process::id(),
                NEXT_DIRECTORY.fetch_add(1, std::sync::atomic::Ordering::SeqCst)
            )));
            std::fs::create_dir(&directory.0).unwrap();
            let checkpoint = directory.0.join("completed.json");
            let mut broker = MemoryBroker::default();
            let result = with_guest(&source, || {
                runner::execute(admitted(), &mut broker, &checkpoint)
            });
            assert_eq!(result.unwrap_err(), expected);
            assert_eq!(broker.reads, 1);
            assert_eq!(broker.published, 0);
            assert!(!checkpoint.exists());
            assert!(crate::checkpoint::Store::open(&checkpoint, 16384)
                .unwrap()
                .load()
                .unwrap()
                .is_none());
        }
    }

    #[test]
    fn scoped_fixture_is_cleared_after_unwinding() {
        // resume_unwind skips the panic hook: a hook message on stderr can split another test's
        // "test ... ok" line in the qualification log.
        let panic = std::panic::catch_unwind(|| {
            with_guest(&guest("unreachable"), || {
                std::panic::resume_unwind(Box::new("fixture"))
            })
        });
        assert!(panic.is_err());
        assert!(OVERRIDE.with(|current| current.borrow().is_none()));
        let mut values = [1.0];
        convert_values(&mut values, 2.0, 1.0).unwrap();
        assert_eq!(values, [3.0]);
    }
}
