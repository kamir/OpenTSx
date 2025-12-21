import os
import json
import requests
import uuid
from typing import Dict, Any, Optional

from app.core.config import settings

class FlinkService:
    def __init__(self, flink_url: str = "http://localhost:8081"):
        self.base_url = flink_url
        self.jar_path = "/app/opentsx-flink-core-3.0.0.jar" # Path inside docker container
        self.jar_id = None

    def _upload_jar(self) -> str:
        """Uploads the OpenTSx Core JAR to Flink."""
        if not os.path.exists(self.jar_path):
             # Fallback for local dev
             fallback_path = "../opentsx-flink-core/target/opentsx-flink-core-3.0.0.jar"
             if os.path.exists(fallback_path):
                 self.jar_path = fallback_path
             else:
                 raise FileNotFoundError(f"Flink JAR not found at {self.jar_path}")

        with open(self.jar_path, "rb") as f:
            files = {"jarfile": (os.path.basename(self.jar_path), f, "application/java-archive")}
            response = requests.post(f"{self.base_url}/jars/upload", files=files)
            response.raise_for_status()
            return response.json()["filename"].split("/")[-1]

    def submit_job(self, pfd: Dict[str, Any]) -> str:
        """
        Submits a dynamic Flink job based on the PFD.
        
        1. Uploads JAR (if not cached/known)
        2. Uploads PFD JSON as a temporary file (or passes as string arg if short, 
           but file is safer. actually REST API takes programArgs)
        
        For simplicity, we'll write the PFD to a reachable path or pass key args. 
        But `DynamicTopologyJob` reads a file path. 
        So we need to make the PFD available to the Flink JobManager/TaskManagers.
        
        Alternative: Modify DynamicTopologyJob to accept JSON string directly?
        Let's assume we pass the JSON content as a string for now, to avoid shared fs issues.
        """
        
        # NOTE: WE NEED TO UPDATE DynamicTopologyJob to accept JSON string if we do this.
        # OR we upload the JSON file too? Flink doesnt support arbitrary file upload easily for distinct args.
        
        # Let's try to pass the JSON string as an argument.
        # But command line args might have length limits. 
        # For now, let's just log what we would do or assume we can write to a shared volume.
        
        try:
            # 1. Upload Jar
            # Optimization: could cache jar_id
            jar_id = self._upload_jar()
            
            # 2. Write PFD to temp file
            pfd_json_str = json.dumps(pfd)
            
            # Strategy: pass the JSON content itself as argument, 
            # modifying Java to differentiate between file path and raw JSON.
            # Or just rely on shared volume.
            
            # For this MVP, let's assume raw JSON string is passed and Java handles it (TODO: update Java)
            
            entry_class = "org.opentsx.flink.pfd.DynamicTopologyJob"
            program_args = f"--json '{pfd_json_str}'" 
            
            payload = {
                "entryClass": entry_class,
                "programArgs": program_args
            }
            
            response = requests.post(f"{self.base_url}/jars/{jar_id}/run", json=payload)
            response.raise_for_status()
            
            return response.json().get("jobid", "unknown")
            
        except Exception as e:
            print(f"Failed to submit Flink job: {e}")
            # Reraise or return None depending on error handling strategy
            raise e

# Singleton instance
flink_service = FlinkService()
