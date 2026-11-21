import importlib.util
from pathlib import Path
import subprocess
import sys
import unittest


class PhysicalDeploymentVectorsTest(unittest.TestCase):
    def test_generator_reproduces_shared_vectors(self):
        script = Path(__file__).with_name("physical_deployment_vectors.py")
        result = subprocess.run([sys.executable, str(script), "--check"], capture_output=True, text=True, check=True)
        self.assertIn("physical deployment vectors", result.stdout)
        spec = importlib.util.spec_from_file_location("physical_vectors", script)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.assertGreater(len(module.vectors()["cases"]), 0)

    def test_computation_retains_signed_zero_and_array_order(self):
        script = Path(__file__).with_name("physical_deployment_vectors.py")
        spec = importlib.util.spec_from_file_location("physical_vectors", script)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.assertNotEqual(module.computation(0.0), module.computation(-0.0))
        self.assertNotEqual(module.computation(["left", "right"]), module.computation(["right", "left"]))


if __name__ == "__main__":
    unittest.main()
