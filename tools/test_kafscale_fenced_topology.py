from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]


def block(text, name, indentation):
    padding = " " * indentation
    expression = rf"^{padding}{re.escape(name)}:[^\n]*\n(.*?)(?=^{padding}\S|\Z)"
    match = re.search(expression, text, flags=re.MULTILINE | re.DOTALL)
    if match is None:
        raise AssertionError(f"missing configuration block: {name}")
    return match.group(1)


def network_names(service):
    section = block(service, "networks", 4)
    return set(re.findall(r"^      ([A-Za-z0-9_-]+):", section, flags=re.MULTILINE))


class KafScaleFencedTopologyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = (ROOT / "docker-compose.wayang-rust.yml").read_text()
        cls.services = block(cls.text, "services", 0)

    def service(self, name):
        return block(self.services, name, 2)

    def test_broker_and_metadata_have_no_published_or_worker_network_endpoint(self):
        broker = self.service("kafscale-broker")
        metadata = self.service("etcd")
        for value in (broker, metadata):
            self.assertNotRegex(value, re.compile(r"^    ports:", re.MULTILINE), msg=value)
            self.assertEqual({"broker_net"}, network_names(value))
        self.assertIn('KAFSCALE_PRINCIPAL_SOURCE: remote_addr', broker)
        self.assertIn('KAFSCALE_ACL_ENABLED: "true"', broker)
        self.assertIn('KAFSCALE_ACL_FAIL_OPEN: "false"', broker)
        self.assertIn('KAFSCALE_AUTO_CREATE_TOPICS: "false"', broker)
        self.assertIn('KAFSCALE_ACL_FILE: /settings/publication-acl.json', broker)
        self.assertIn('/publication-acl.json:/settings/publication-acl.json:ro', broker)

    def test_read_and_write_proxies_have_disjoint_client_networks_and_fixed_broker_identity(self):
        read = self.service("kafscale-read")
        write = self.service("kafscale-write")
        self.assertEqual({"broker_net", "worker_net"}, network_names(read))
        self.assertEqual({"broker_net", "writer_net", "proxy_host_net"}, network_names(write))
        self.assertIn("ipv4_address: 172.29.73.10", read)
        self.assertIn("ipv4_address: 172.29.73.11", write)
        self.assertIn("KAFSCALE_PROXY_ADVERTISED_HOST: kafscale-read", read)
        self.assertIn("KAFSCALE_PROXY_ADVERTISED_HOST: kafscale-write", write)
        self.assertNotRegex(read, re.compile(r"^    ports:", re.MULTILINE))
        self.assertIn('"127.0.0.1:${KAFSCALE_BROKER_PORT:-39093}:${KAFSCALE_BROKER_PORT:-39093}"', write)
        for value in (read, write):
            self.assertIn("entrypoint: [/transport/kafscale-proxy]", value)
            self.assertIn("KAFSCALE_PROXY_BACKENDS: kafscale-broker:19092", value)
            self.assertIn("KAFSCALE_PROXY_ETCD_ENDPOINTS: http://etcd:2379", value)

    def test_workers_have_only_the_read_network_and_fenced_publication(self):
        defaults = block(self.text, "x-runner", 0)
        self.assertIn("cap_drop: [ALL]", defaults)
        self.assertIn('security_opt: ["no-new-privileges:true"]', defaults)
        self.assertIn("read_only: true", defaults)
        for index in range(1, 4):
            worker = self.service(f"runner-{index}")
            self.assertEqual({"worker_net"}, network_names(worker))
            self.assertIn(f"ipv4_address: 172.29.74.{10 + index}", worker)
            self.assertIn('--bootstrap, "kafscale-read:', worker)
            self.assertIn('--publisher, "http://wayang-publication:8096"', worker)
            self.assertIn('--controller, "172.29.74.10"', worker)
            self.assertNotIn("run-token", worker)
            self.assertNotIn("publication-acl", worker)
            self.assertNotIn("/publication", worker)
            self.assertNotIn("network_mode:", worker)
            self.assertNotIn("privileged:", worker)

    def test_gateway_has_separate_interfaces_and_exclusive_private_state(self):
        driver = self.service("wayang")
        self.assertEqual({"worker_net", "writer_net", "control_net"}, network_names(driver))
        for address in ("172.29.74.10", "172.29.75.10", "172.29.76.10"):
            self.assertIn("ipv4_address: " + address, driver)
        self.assertIn("aliases: [wayang-publication]", driver)
        self.assertIn('/run-token:/settings/run-token:ro', driver)
        self.assertIn('/publication:/publication', driver)
        self.assertNotRegex(driver, re.compile(r"^    ports:", re.MULTILINE))
        python = block(self.text, "x-python", 0)
        self.assertIn('DAG_RUN_TOKEN: "${DAG_RUN_TOKEN:?Set DAG_RUN_TOKEN}"', python)
        self.assertIn("networks: [control_net]", python)

    def test_role_networks_are_internal_and_have_distinct_subnets(self):
        networks = block(self.text, "networks", 0)
        for index, name in enumerate(("broker_net", "worker_net", "writer_net", "control_net"), start=73):
            value = block(networks, name, 2)
            self.assertIn("internal: true", value)
            self.assertIn(f"subnet: 172.29.{index}.0/24", value)

    def test_host_publishing_bridges_are_separate_and_not_internal(self):
        networks = block(self.text, "networks", 0)
        for name in ("proxy_host_net", "ui_host_net"):
            self.assertIn("internal: false", block(networks, name, 2))

    def test_host_publishing_bridges_have_only_their_single_listener_service(self):
        names = re.findall(r"^  ([A-Za-z0-9_-]+):", self.services, flags=re.MULTILINE)
        for network, expected in (("proxy_host_net", "kafscale-write"), ("ui_host_net", "frontend")):
            members = {name for name in names if re.search(rf"^      {network}:", self.service(name), re.MULTILINE)}
            self.assertEqual({expected}, members)
        self.assertEqual({"control_net", "ui_host_net"}, network_names(self.service("frontend")))


if __name__ == "__main__":
    unittest.main()
