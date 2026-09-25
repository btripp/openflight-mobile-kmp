# OpenFlight BLE contract fixtures (copied from the backend)

These files are **copied verbatim** from the OpenFlight backend and are the cross-language
contract for the BLE and SSE wire format (plan R8e). Don't edit them here.

| Here | Backend source |
|---|---|
| `ble_goldens/*.json` | `tests/fixtures/ble_goldens/*.json` |
| `shot_v1.json` | `tests/fixtures/shot_v1.json` |
| `shot_v2.json` | `tests/fixtures/shot_v2.json` |

Source: branch `feat/phone-connectivity`, commit `07d5313` (the fixtures last changed in
`96cab45`). The backend generates the `server_to_client` goldens with
`scripts/ble/generate_goldens.py`; the `client_to_server` ones are hand-built client frames that
its tests reassemble and dispatch.

The build turns every JSON file here into the generated `BleContractFixtures` object
(`build-logic` `GenerateJsonFixturesTask`), so `commonTest` reads them on the Android host and the
iOS simulator alike. `core:ble` generates the same object from this directory.

## Refresh

```bash
BACKEND=~/Developer/oss/openflight-ble            # a checkout of the backend branch
(cd "$BACKEND" && uv run python scripts/ble/generate_goldens.py --check)
cp "$BACKEND"/tests/fixtures/ble_goldens/*.json core/protocol/src/commonTest/fixtures/openflight-ble/ble_goldens/
cp "$BACKEND"/tests/fixtures/shot_v1.json "$BACKEND"/tests/fixtures/shot_v2.json \
   core/protocol/src/commonTest/fixtures/openflight-ble/
./gradlew :core:protocol:allTests :core:ble:allTests
```

Then update the commit above. A new golden file is picked up automatically: the tests iterate
over every `server_to_client` file.
