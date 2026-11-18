use std::collections::BTreeMap;
use std::io::{Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::time::{Duration, Instant};

const MAX_FRAME: usize = 1_048_576;
const TIMEOUT: Duration = Duration::from_secs(30);

pub fn committed_after_restart(
    bootstrap: &str,
    group: &str,
    topic: &str,
    read_first: impl FnOnce() -> Result<(), String>,
) -> Result<i64, String> {
    let mut client = Client::connect(bootstrap)?;
    client.versions()?;
    let mut find = Vec::new();
    compact(&mut find, group.as_bytes())?;
    find.extend_from_slice(&[0, 0]);
    let response = client.request(10, 3, &find)?;
    let mut reader = Reader::new(&response);
    reader.nonnegative()?;
    reader.success()?;
    if let Some(message) = reader.nullable_compact()? {
        std::str::from_utf8(message).map_err(|_| "the coordinator error text is not UTF-8")?;
    }
    let node = reader.i32()?;
    let host =
        std::str::from_utf8(reader.compact()?).map_err(|_| "the coordinator host is not UTF-8")?;
    if node < 0 || host.is_empty() || !(1..=65535).contains(&reader.i32()?) {
        return Err("invalid coordinator identity".into());
    }
    reader.tags()?;
    reader.finish()?;

    let mut member = String::new();
    let deadline = Instant::now() + TIMEOUT;
    let generation = loop {
        if Instant::now() >= deadline {
            return Err("group membership did not stabilize within 30 seconds".into());
        }
        let response = client.request(11, 4, &join_body(group, topic, &member)?)?;
        let joined = parse_join(&response)?;
        if joined.member.is_empty() {
            return Err("the group response omitted its member identity".into());
        }
        member = joined.member;
        if joined.error == 27 || joined.error == 79 {
            continue;
        }
        if joined.error != 0
            || joined.generation < 0
            || joined.protocol != "range"
            || joined.leader != member
            || joined.members != [member.clone()]
        {
            return Err(format!(
                "group admission failed with error {}",
                joined.error
            ));
        }
        break joined.generation;
    };
    let response = client.request(14, 4, &sync_body(group, topic, &member, generation)?)?;
    let assignment = parse_sync(&response)?;
    validate_assignment(&assignment, topic)?;
    read_first()?;

    let response = client.request(8, 3, &commit_body(group, topic, &member, generation)?)?;
    parse_commit(&response, topic)?;
    drop(client);

    let mut restarted = Client::connect(bootstrap)?;
    let mut fetch = Vec::new();
    string(&mut fetch, group)?;
    fetch.extend_from_slice(&1_i32.to_be_bytes());
    string(&mut fetch, topic)?;
    fetch.extend_from_slice(&1_i32.to_be_bytes());
    fetch.extend_from_slice(&0_i32.to_be_bytes());
    let response = restarted.request(9, 5, &fetch)?;
    parse_fetch(&response, topic)
}

fn string(output: &mut Vec<u8>, value: &str) -> Result<(), String> {
    let length = i16::try_from(value.len()).map_err(|_| "Kafka string exceeds its size limit")?;
    output.extend_from_slice(&length.to_be_bytes());
    output.extend_from_slice(value.as_bytes());
    Ok(())
}

fn compact(output: &mut Vec<u8>, value: &[u8]) -> Result<(), String> {
    let mut length = u32::try_from(value.len())
        .map_err(|_| "compact field exceeds its size limit")?
        .checked_add(1)
        .ok_or("compact field length overflow")?;
    while length >= 128 {
        output.push((length as u8 & 127) | 128);
        length >>= 7;
    }
    output.push(length as u8);
    output.extend_from_slice(value);
    Ok(())
}

fn join_body(group: &str, topic: &str, member: &str) -> Result<Vec<u8>, String> {
    let mut subscription = 0_i16.to_be_bytes().to_vec();
    subscription.extend_from_slice(&1_i32.to_be_bytes());
    string(&mut subscription, topic)?;
    subscription.extend_from_slice(&0_i32.to_be_bytes());
    let mut body = Vec::new();
    string(&mut body, group)?;
    body.extend_from_slice(&60_000_i32.to_be_bytes());
    body.extend_from_slice(&30_000_i32.to_be_bytes());
    string(&mut body, member)?;
    string(&mut body, "consumer")?;
    body.extend_from_slice(&1_i32.to_be_bytes());
    string(&mut body, "range")?;
    body.extend_from_slice(
        &i32::try_from(subscription.len())
            .map_err(|_| "subscription too large")?
            .to_be_bytes(),
    );
    body.extend_from_slice(&subscription);
    Ok(body)
}

fn sync_body(group: &str, topic: &str, member: &str, generation: i32) -> Result<Vec<u8>, String> {
    let mut assignment = 0_i16.to_be_bytes().to_vec();
    assignment.extend_from_slice(&1_i32.to_be_bytes());
    string(&mut assignment, topic)?;
    assignment.extend_from_slice(&1_i32.to_be_bytes());
    assignment.extend_from_slice(&0_i32.to_be_bytes());
    assignment.extend_from_slice(&0_i32.to_be_bytes());
    let mut body = Vec::new();
    compact(&mut body, group.as_bytes())?;
    body.extend_from_slice(&generation.to_be_bytes());
    compact(&mut body, member.as_bytes())?;
    body.extend_from_slice(&[0, 2]);
    compact(&mut body, member.as_bytes())?;
    compact(&mut body, &assignment)?;
    body.extend_from_slice(&[0, 0]);
    Ok(body)
}

fn commit_body(group: &str, topic: &str, member: &str, generation: i32) -> Result<Vec<u8>, String> {
    let mut body = Vec::new();
    string(&mut body, group)?;
    body.extend_from_slice(&generation.to_be_bytes());
    string(&mut body, member)?;
    body.extend_from_slice(&(-1_i64).to_be_bytes());
    body.extend_from_slice(&1_i32.to_be_bytes());
    string(&mut body, topic)?;
    body.extend_from_slice(&1_i32.to_be_bytes());
    body.extend_from_slice(&0_i32.to_be_bytes());
    body.extend_from_slice(&4_i64.to_be_bytes());
    body.extend_from_slice(&(-1_i16).to_be_bytes());
    Ok(body)
}

struct Joined {
    error: i16,
    generation: i32,
    protocol: String,
    leader: String,
    member: String,
    members: Vec<String>,
}

fn parse_join(bytes: &[u8]) -> Result<Joined, String> {
    let mut reader = Reader::new(bytes);
    reader.nonnegative()?;
    let error = reader.i16()?;
    let generation = reader.i32()?;
    let protocol = reader.string()?.to_string();
    let leader = reader.string()?.to_string();
    let member = reader.string()?.to_string();
    let count = reader.count()?;
    let mut members = Vec::with_capacity(count);
    for _ in 0..count {
        members.push(reader.string()?.to_string());
        reader.bytes()?;
    }
    reader.finish()?;
    Ok(Joined {
        error,
        generation,
        protocol,
        leader,
        member,
        members,
    })
}

fn parse_sync(bytes: &[u8]) -> Result<Vec<u8>, String> {
    let mut reader = Reader::new(bytes);
    reader.nonnegative()?;
    reader.success()?;
    let assignment = reader.compact()?.to_vec();
    reader.tags()?;
    reader.finish()?;
    Ok(assignment)
}

fn validate_assignment(bytes: &[u8], topic: &str) -> Result<(), String> {
    let mut reader = Reader::new(bytes);
    if reader.i16()? != 0
        || reader.i32()? != 1
        || reader.string()? != topic
        || reader.i32()? != 1
        || reader.i32()? != 0
        || !reader.bytes()?.is_empty()
    {
        return Err("the group did not assign the exact test partition".into());
    }
    reader.finish()
}

fn parse_commit(bytes: &[u8], topic: &str) -> Result<(), String> {
    let mut reader = Reader::new(bytes);
    reader.nonnegative()?;
    if reader.i32()? != 1 || reader.string()? != topic || reader.i32()? != 1 || reader.i32()? != 0 {
        return Err("the commit response changed the test partition".into());
    }
    reader.success()?;
    reader.finish()
}

fn parse_fetch(bytes: &[u8], topic: &str) -> Result<i64, String> {
    let mut reader = Reader::new(bytes);
    reader.nonnegative()?;
    if reader.i32()? != 1 || reader.string()? != topic || reader.i32()? != 1 || reader.i32()? != 0 {
        return Err("the offset response changed the test partition".into());
    }
    let offset = reader.i64()?;
    reader.i32()?;
    reader.nullable_string()?;
    reader.success()?;
    reader.success()?;
    reader.finish()?;
    if offset != 4 {
        return Err(format!(
            "committed offset differs: expected 4, got {offset}"
        ));
    }
    Ok(offset)
}

struct Client {
    stream: TcpStream,
    correlation: i32,
}

impl Client {
    fn connect(bootstrap: &str) -> Result<Self, String> {
        let endpoint = bootstrap.split(',').next().ok_or("missing test endpoint")?;
        let address = endpoint
            .to_socket_addrs()
            .map_err(|e| format!("cannot resolve the test endpoint: {e}"))?
            .next()
            .ok_or("the test endpoint resolved to no address")?;
        let stream = TcpStream::connect_timeout(&address, TIMEOUT)
            .map_err(|e| format!("cannot connect the group probe to the test endpoint: {e}"))?;
        stream
            .set_read_timeout(Some(TIMEOUT))
            .map_err(|e| format!("cannot set the group read timeout: {e}"))?;
        stream
            .set_write_timeout(Some(TIMEOUT))
            .map_err(|e| format!("cannot set the group write timeout: {e}"))?;
        Ok(Self {
            stream,
            correlation: 0,
        })
    }

    fn request(&mut self, key: i16, version: i16, body: &[u8]) -> Result<Vec<u8>, String> {
        let flexible = match (key, version) {
            (18, 0) | (11, 4) | (8, 3) | (9, 5) => false,
            (10, 3) | (14, 4) => true,
            _ => return Err("the group fixture does not implement that API version".into()),
        };
        self.correlation = self
            .correlation
            .checked_add(1)
            .ok_or("correlation id overflow")?;
        let mut request = Vec::new();
        request.extend_from_slice(&key.to_be_bytes());
        request.extend_from_slice(&version.to_be_bytes());
        request.extend_from_slice(&self.correlation.to_be_bytes());
        string(&mut request, "opentsx-b1-conformance")?;
        if flexible {
            request.push(0);
        }
        request.extend_from_slice(body);
        if request.len() > MAX_FRAME {
            return Err("the group request exceeds its frame limit".into());
        }
        self.stream
            .write_all(
                &i32::try_from(request.len())
                    .map_err(|_| "request length overflow")?
                    .to_be_bytes(),
            )
            .and_then(|_| self.stream.write_all(&request))
            .map_err(|e| format!("cannot write the group request: {e}"))?;
        let mut prefix = [0; 4];
        self.stream
            .read_exact(&mut prefix)
            .map_err(|e| format!("cannot read the group frame length: {e}"))?;
        let length = frame_length(prefix)?;
        let mut frame = vec![0; length];
        self.stream
            .read_exact(&mut frame)
            .map_err(|e| format!("cannot read the complete group frame: {e}"))?;
        response_body(&frame, self.correlation, flexible).map(<[u8]>::to_vec)
    }

    fn versions(&mut self) -> Result<(), String> {
        let response = self.request(18, 0, &[])?;
        let mut reader = Reader::new(&response);
        reader.success()?;
        let mut versions = BTreeMap::new();
        for _ in 0..reader.count()? {
            let key = reader.i16()?;
            let minimum = reader.i16()?;
            let maximum = reader.i16()?;
            if minimum > maximum || versions.insert(key, (minimum, maximum)).is_some() {
                return Err("the test endpoint returned invalid API versions".into());
            }
        }
        reader.finish()?;
        for (key, required) in [(10, 3), (11, 4), (14, 4), (8, 3), (9, 5)] {
            if versions
                .get(&key)
                .is_none_or(|(minimum, maximum)| required < *minimum || required > *maximum)
            {
                return Err(format!(
                    "the test endpoint does not advertise API {key} version {required}"
                ));
            }
        }
        Ok(())
    }
}

fn frame_length(prefix: [u8; 4]) -> Result<usize, String> {
    let length = i32::from_be_bytes(prefix);
    if !(4..=MAX_FRAME as i32).contains(&length) {
        return Err("the group response has an invalid frame length".into());
    }
    Ok(length as usize)
}

fn response_body(frame: &[u8], correlation: i32, flexible: bool) -> Result<&[u8], String> {
    let mut reader = Reader::new(frame);
    if reader.i32()? != correlation {
        return Err("the group response correlation does not match".into());
    }
    if flexible {
        reader.tags()?;
    }
    Ok(&frame[reader.position..])
}

struct Reader<'a> {
    bytes: &'a [u8],
    position: usize,
}

impl<'a> Reader<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, position: 0 }
    }

    fn take(&mut self, count: usize) -> Result<&'a [u8], String> {
        let end = self
            .position
            .checked_add(count)
            .ok_or("group field length overflow")?;
        let value = self
            .bytes
            .get(self.position..end)
            .ok_or("truncated group response")?;
        self.position = end;
        Ok(value)
    }

    fn i16(&mut self) -> Result<i16, String> {
        Ok(i16::from_be_bytes(
            self.take(2)?.try_into().map_err(|_| "invalid i16")?,
        ))
    }
    fn i32(&mut self) -> Result<i32, String> {
        Ok(i32::from_be_bytes(
            self.take(4)?.try_into().map_err(|_| "invalid i32")?,
        ))
    }
    fn i64(&mut self) -> Result<i64, String> {
        Ok(i64::from_be_bytes(
            self.take(8)?.try_into().map_err(|_| "invalid i64")?,
        ))
    }

    fn nonnegative(&mut self) -> Result<(), String> {
        if self.i32()? < 0 {
            Err("negative Kafka throttle duration".into())
        } else {
            Ok(())
        }
    }

    fn success(&mut self) -> Result<(), String> {
        let code = self.i16()?;
        if code != 0 {
            Err(format!("Kafka group control error {code}"))
        } else {
            Ok(())
        }
    }

    fn count(&mut self) -> Result<usize, String> {
        let count = self.i32()?;
        if !(0..=128).contains(&count) {
            return Err("group array count exceeds its limit".into());
        }
        Ok(count as usize)
    }

    fn string(&mut self) -> Result<&'a str, String> {
        let length = self.i16()?;
        if length < 0 {
            return Err("unexpected null group string".into());
        }
        std::str::from_utf8(self.take(length as usize)?)
            .map_err(|_| "group string is not UTF-8".into())
    }

    fn nullable_string(&mut self) -> Result<Option<&'a str>, String> {
        let length = self.i16()?;
        if length == -1 {
            return Ok(None);
        }
        if length < 0 {
            return Err("invalid nullable string length".into());
        }
        Ok(Some(
            std::str::from_utf8(self.take(length as usize)?)
                .map_err(|_| "group string is not UTF-8")?,
        ))
    }

    fn bytes(&mut self) -> Result<&'a [u8], String> {
        let length = self.i32()?;
        if !(0..=MAX_FRAME as i32).contains(&length) {
            return Err("group byte field exceeds its limit".into());
        }
        self.take(length as usize)
    }

    fn varint(&mut self) -> Result<u32, String> {
        let mut value = 0_u32;
        for shift in (0..35).step_by(7) {
            let byte = self.take(1)?[0];
            if shift == 28 && byte > 15 {
                return Err("group varint overflow".into());
            }
            value |= u32::from(byte & 127) << shift;
            if byte < 128 {
                if shift != 0 && byte == 0 {
                    return Err("noncanonical group varint".into());
                }
                return Ok(value);
            }
        }
        Err("group varint overflow".into())
    }

    fn compact(&mut self) -> Result<&'a [u8], String> {
        let length = self
            .varint()?
            .checked_sub(1)
            .ok_or("unexpected null compact field")?;
        if length as usize > MAX_FRAME {
            return Err("compact group field exceeds its limit".into());
        }
        self.take(length as usize)
    }

    fn nullable_compact(&mut self) -> Result<Option<&'a [u8]>, String> {
        let length = self.varint()?;
        if length == 0 {
            return Ok(None);
        }
        if length as usize > MAX_FRAME {
            return Err("compact group field exceeds its limit".into());
        }
        self.take(length as usize - 1).map(Some)
    }

    fn tags(&mut self) -> Result<(), String> {
        let count = self.varint()?;
        if count > 128 {
            return Err("too many group response tags".into());
        }
        let mut previous = None;
        for _ in 0..count {
            let tag = self.varint()?;
            if previous.is_some_and(|value| value >= tag) {
                return Err("group response tags are not strictly ordered".into());
            }
            let length = self.varint()?;
            self.take(length as usize)?;
            previous = Some(tag);
        }
        Ok(())
    }

    fn finish(&self) -> Result<(), String> {
        if self.position != self.bytes.len() {
            Err("trailing group response bytes".into())
        } else {
            Ok(())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frames_and_correlations_are_bounded_and_exact() {
        for length in [-1_i32, 0, 3, MAX_FRAME as i32 + 1] {
            assert!(frame_length(length.to_be_bytes()).is_err());
        }
        assert_eq!(frame_length(4_i32.to_be_bytes()).unwrap(), 4);
        assert!(response_body(&1_i32.to_be_bytes(), 2, false).is_err());
        assert!(response_body(&[0, 0, 0], 0, false).is_err());
        assert!(response_body(&[0, 0, 0, 1], 1, true).is_err());
        assert_eq!(response_body(&[0, 0, 0, 1, 0, 99], 1, true).unwrap(), &[99]);
    }

    #[test]
    fn malformed_lengths_varints_utf8_and_tags_are_rejected() {
        for value in [vec![128, 0], vec![255, 255, 255, 255, 31], vec![128]] {
            assert!(Reader::new(&value).varint().is_err());
        }
        for value in [vec![0], vec![3, 1], vec![255, 255, 255, 255, 15]] {
            assert!(Reader::new(&value).compact().is_err());
        }
        assert!(Reader::new(&[0, 1, 255]).string().is_err());
        assert!(Reader::new(&[255, 254]).nullable_string().is_err());
        assert!(Reader::new(&[2, 1, 0, 1, 0]).tags().is_err());
        assert!(Reader::new(&[1, 0, 2, 1]).tags().is_err());
        assert!(Reader::new(&129_i32.to_be_bytes()).count().is_err());
        assert!(Reader::new(&(-1_i32).to_be_bytes()).bytes().is_err());
    }

    #[test]
    fn committed_response_requires_the_exact_topic_partition_offset_and_success() {
        let topic = "tsx.b1.episodes.v2";
        let mut response = 0_i32.to_be_bytes().to_vec();
        response.extend_from_slice(&1_i32.to_be_bytes());
        string(&mut response, topic).unwrap();
        response.extend_from_slice(&1_i32.to_be_bytes());
        response.extend_from_slice(&0_i32.to_be_bytes());
        response.extend_from_slice(&4_i64.to_be_bytes());
        response.extend_from_slice(&(-1_i32).to_be_bytes());
        response.extend_from_slice(&(-1_i16).to_be_bytes());
        response.extend_from_slice(&[0, 0, 0, 0]);
        assert_eq!(parse_fetch(&response, topic).unwrap(), 4);
        assert!(parse_fetch(&response, "other").is_err());
        for length in 0..response.len() {
            assert!(parse_fetch(&response[..length], topic).is_err());
        }
        let mut trailing = response.clone();
        trailing.push(0);
        assert!(parse_fetch(&trailing, topic).is_err());
        for index in [7, 9, response.len() - 1, response.len() - 3] {
            let mut changed = response.clone();
            changed[index] ^= 1;
            assert!(parse_fetch(&changed, topic).is_err());
        }
        let offset_at = 4 + 4 + 2 + topic.len() + 4 + 4;
        let mut changed = response.clone();
        changed[offset_at + 7] = 5;
        assert!(parse_fetch(&changed, topic).is_err());
        let mut throttled = response.clone();
        throttled[3] = 1;
        assert_eq!(parse_fetch(&throttled, topic).unwrap(), 4);
        let mut negative_throttle = response.clone();
        negative_throttle[..4].copy_from_slice(&(-1_i32).to_be_bytes());
        assert!(parse_fetch(&negative_throttle, topic).is_err());
    }

    #[test]
    fn request_versions_and_membership_fields_match_the_test_contract() {
        let body = join_body("group", "topic", "member").unwrap();
        let mut reader = Reader::new(&body);
        assert_eq!(reader.string().unwrap(), "group");
        assert_eq!(reader.i32().unwrap(), 60_000);
        assert_eq!(reader.i32().unwrap(), 30_000);
        assert_eq!(reader.string().unwrap(), "member");
        assert_eq!(reader.string().unwrap(), "consumer");
        assert_eq!(reader.i32().unwrap(), 1);
        assert_eq!(reader.string().unwrap(), "range");
        let subscription = reader.bytes().unwrap();
        let mut subscription = Reader::new(subscription);
        assert_eq!(subscription.i16().unwrap(), 0);
        assert_eq!(subscription.i32().unwrap(), 1);
        assert_eq!(subscription.string().unwrap(), "topic");
        assert!(subscription.bytes().unwrap().is_empty());
        subscription.finish().unwrap();
        reader.finish().unwrap();
        let body = commit_body("group", "topic", "member", 7).unwrap();
        let mut reader = Reader::new(&body);
        assert_eq!(reader.string().unwrap(), "group");
        assert_eq!(reader.i32().unwrap(), 7);
        assert_eq!(reader.string().unwrap(), "member");
        assert_eq!(reader.i64().unwrap(), -1);
    }
}
