"""Back up source and pinned native dependencies; models are supplied separately."""
from datetime import datetime
from pathlib import Path
import hashlib
import os
import zipfile

root = Path(__file__).resolve().parents[1]
destination = root / "deliverables"
destination.mkdir(exist_ok=True)
output = destination / ("LocalVoices-source-" + datetime.now().strftime("%Y%m%d-%H%M%S") + ".zip")
excluded = {".git", ".gradle", ".kotlin", ".tools", ".research", ".cxx", "build", "models", "deliverables", ".idea", "__pycache__"}
count = 0
with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=5) as archive:
    for directory, dirs, files in os.walk(root):
        dirs[:] = [name for name in dirs if name not in excluded]
        for name in files:
            file = Path(directory) / name
            if name == "local.properties" or file.suffix in {".pyc", ".iml"}:
                continue
            archive.write(file, file.relative_to(root).as_posix())
            count += 1
with zipfile.ZipFile(output) as archive:
    assert archive.testzip() is None, "Backup CRC check failed"
digest = hashlib.file_digest(output.open("rb"), "sha256").hexdigest()
output.with_suffix(".zip.sha256").write_text(digest + "  " + output.name + "\n", encoding="utf-8")
print(f"{output}\n{count} files; {output.stat().st_size} bytes; SHA256 {digest}")
