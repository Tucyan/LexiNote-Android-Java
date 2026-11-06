# ECDICT offline dictionary

The bundled dictionary is derived from [ECDICT](https://github.com/skywind3000/ECDICT), commit `39cfb9672c0906b63569e0453c2e6abfb9ab182a`, under the MIT license. The complete upstream license and copyright notice are included in `app/src/main/assets/ECDICT-LICENSE.txt`.

Input: `ecdict.csv` at that commit, 66,009,548 bytes, SHA-256 `d1ecbdf9ee2b920afb6f430ca6b6bb9f559588cf0dff86e04bd255f9390843cc`.

Rebuild with Python 3.11 or later: `python scripts/build-ecdict.py path/to/ecdict.csv app/src/main/assets/ecdict.db`. The script verifies the hash and does not download data. It includes entries with a definition and a frequency rank within 50,000, Oxford/Collins/exam tags, plus phrases of up to three words. Inflections are taken only from explicit exchange fields; ambiguous mappings are omitted and exact entries take precedence. Chinese and English definitions remain independent, because the source does not guarantee line-by-line correspondence.

Measured output: 395,217 entries, 38,903 unambiguous variants, 34,422,784 bytes uncompressed. A standalone ZIP using DEFLATE level 9 measured 14,136,075 bytes; actual APK increment depends on packaging. Examples checked include `bank`, `run`, `look up`, `take care of`, and `went → go`. `running` exists as its own entry and therefore wins over its `run` variant. This selection does not include all ECDICT entries or all longer phrases.
