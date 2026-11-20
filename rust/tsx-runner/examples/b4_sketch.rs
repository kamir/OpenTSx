use serde::Deserialize;
use serde_json::{json, Value};
use std::collections::BTreeSet;
use std::io::{Read, Write};
use tsx_runner::stats::StatsAccumulator;

const MAX_BYTES: usize = 4_194_304;
const MAX_CASES: usize = 128;
const MAX_SAMPLES: usize = 100_000;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Request {
    schema: String,
    cases: Vec<Case>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Case {
    name: String,
    samples: Vec<Sample>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Sample {
    timestamp_micros: i64,
    value_bits: String,
}

fn bits(value: f64) -> String {
    format!("{:016x}", value.to_bits())
}

fn evaluate(bytes: &[u8]) -> Result<Value, String> {
    if bytes.len() > MAX_BYTES {
        return Err("B4 sketch input exceeds 4194304 bytes".into());
    }
    let request: Request = serde_json::from_slice(bytes)
        .map_err(|error| format!("invalid B4 sketch input: {error}"))?;
    if request.schema != "opentsx.rust-b4-sketch-input.v1" {
        return Err("unsupported B4 sketch input schema".into());
    }
    if request.cases.is_empty() || request.cases.len() > MAX_CASES {
        return Err("B4 sketch input requires 1 to 128 cases".into());
    }
    let mut names = BTreeSet::new();
    let mut samples = 0usize;
    let mut output = Vec::new();
    for case in request.cases {
        if case.name.is_empty()
            || case.name.len() > 80
            || !case
                .name
                .chars()
                .all(|value| value.is_ascii_lowercase() || value.is_ascii_digit() || value == '-')
            || !names.insert(case.name.clone())
        {
            return Err("B4 sketch case names must be unique lowercase identifiers".into());
        }
        samples = samples
            .checked_add(case.samples.len())
            .ok_or("B4 sketch sample count overflow")?;
        if case.samples.len() > 20_000 || samples > MAX_SAMPLES {
            return Err("B4 sketch input exceeds its sample limit".into());
        }
        let mut accumulator = StatsAccumulator::new(0.01)?;
        for sample in &case.samples {
            if !(0..600_000_000).contains(&sample.timestamp_micros) {
                return Err("B4 sample timestamps must be inside the PT10M window".into());
            }
            if sample.value_bits.len() != 16
                || !sample
                    .value_bits
                    .bytes()
                    .all(|value| value.is_ascii_digit() || (b'a'..=b'f').contains(&value))
            {
                return Err("B4 samples require 16 lowercase hexadecimal binary64 bits".into());
            }
            let value = u64::from_str_radix(&sample.value_bits, 16)
                .map_err(|error| format!("invalid B4 sample bits: {error}"))?;
            accumulator.add(sample.timestamp_micros, f64::from_bits(value))?;
        }
        let record = accumulator.to_record("0123456789abcdef", "PT10M", 0, 600_000_000);
        let sketch = record.sketch.as_ref().ok_or("B4 sketch is missing")?;
        let state = serde_json::to_value(sketch)
            .map_err(|error| format!("cannot encode B4 sketch stores: {error}"))?;
        let mut quantiles = Vec::new();
        for q in [0.0, 0.01, 0.5, 0.99, 1.0] {
            quantiles.push(json!({"q":q,"valueBits":bits(sketch.quantile(q)?)}));
        }
        let sketch_hex: String = sketch
            .to_bytes()
            .iter()
            .map(|value| format!("{value:02x}"))
            .collect();
        let soe_hex: String = record
            .encode_soe()?
            .iter()
            .map(|value| format!("{value:02x}"))
            .collect();
        output.push(json!({
            "name":case.name,
            "soeHex":soe_hex,
            "inputCount":case.samples.len(),
            "validCount":record.valid_count,
            "nanCount":record.nan_count,
            "gapCount":record.gap_count,
            "minBits":bits(record.min),
            "maxBits":bits(record.max),
            "meanBits":bits(record.mean),
            "m2Bits":bits(record.m2),
            "lastSeenMicros":if case.samples.is_empty(){None}else{Some(accumulator.last_micros)},
            "sketchHex":sketch_hex,
            "zeroCount":state.get("zero_count").ok_or("B4 zero count is missing")?,
            "positive":state.get("positive").ok_or("B4 positive bins are missing")?,
            "negative":state.get("negative").ok_or("B4 negative bins are missing")?,
            "quantiles":quantiles
        }));
    }
    Ok(
        json!({"schema":"opentsx.rust-b4-sketch-output.v1","version":env!("CARGO_PKG_VERSION"),"alpha":0.01,"maxBins":4096,"cases":output}),
    )
}

fn run() -> Result<(), String> {
    let arguments: Vec<_> = std::env::args().skip(1).collect();
    let output = match arguments.as_slice() {
        [flag] if flag == "--help" => "Usage: b4_sketch < request.json\nRead bounded binary64 sketch qualification cases from standard input.\nInput schema: opentsx.rust-b4-sketch-input.v1; maximum 4194304 bytes, 128 cases, 100000 samples.\n".to_owned(),
        [flag] if flag == "--version" => format!("tsx-runner {}\n", env!("CARGO_PKG_VERSION")),
        [] => {
            let mut bytes = Vec::new();
            std::io::stdin().take((MAX_BYTES + 1) as u64).read_to_end(&mut bytes)
                .map_err(|error|format!("cannot read B4 sketch input: {error}"))?;
            let output = serde_json::to_string(&evaluate(&bytes)?)
                .map_err(|error|format!("cannot encode B4 sketch output: {error}"))?;
            if output.len() > 8_388_608 {
                return Err("B4 sketch output exceeds 8388608 bytes".into());
            }
            output + "\n"
        }
        _ => return Err("expected no arguments, --help or --version".into())
    };
    std::io::stdout()
        .lock()
        .write_all(output.as_bytes())
        .map_err(|error| format!("cannot write B4 sketch output: {error}"))
}

fn main() {
    if let Err(error) = run() {
        eprintln!("{error}");
        std::process::exit(2);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn existing_statistics_preserve_binary64_extrema_and_zero_counts() {
        let input = json!({"schema":"opentsx.rust-b4-sketch-input.v1","cases":[{"name":"zeros","samples":[
            {"timestampMicros":0,"valueBits":"8000000000000000"},
            {"timestampMicros":1,"valueBits":"0000000000000000"},
            {"timestampMicros":2,"valueBits":"7ff8000000000000"}]}]});
        let result = evaluate(input.to_string().as_bytes()).unwrap();
        let case = &result["cases"][0];
        assert_eq!(case["validCount"], 2);
        assert_eq!(case["nanCount"], 1);
        assert_eq!(case["minBits"], "8000000000000000");
        assert_eq!(case["maxBits"], "0000000000000000");
        assert_eq!(case["zeroCount"], 2);
        assert_eq!(case["lastSeenMicros"], 2);
    }

    #[test]
    fn malformed_fields_bits_names_and_schema_are_refused() {
        for input in [
            json!({"schema":"wrong","cases":[]}),
            json!({"schema":"opentsx.rust-b4-sketch-input.v1","cases":[{"name":"bad","samples":[{"timestampMicros":0,"valueBits":"ABC"}]}]}),
            json!({"schema":"opentsx.rust-b4-sketch-input.v1","cases":[{"name":"same","samples":[]},{"name":"same","samples":[]}]}),
            json!({"schema":"opentsx.rust-b4-sketch-input.v1","cases":[{"name":"extra","samples":[]}],"extra":true}),
        ] {
            assert!(evaluate(input.to_string().as_bytes()).is_err());
        }
    }

    #[test]
    fn input_bytes_case_count_and_sample_count_are_bounded() {
        assert!(evaluate(&vec![b' '; MAX_BYTES + 1]).is_err());
        let cases: Vec<_> = (0..129)
            .map(|index| json!({"name":format!("case-{index}"),"samples":[]}))
            .collect();
        assert!(evaluate(
            json!({"schema":"opentsx.rust-b4-sketch-input.v1","cases":cases})
                .to_string()
                .as_bytes()
        )
        .is_err());
        let samples = vec![json!({"timestampMicros":0,"valueBits":"0000000000000000"}); 20_001];
        assert!(evaluate(json!({"schema":"opentsx.rust-b4-sketch-input.v1","cases":[{"name":"many","samples":samples}]}).to_string().as_bytes()).is_err());
    }
}
