# LegadoForLNR

> This project is a derivative of lyc486 Legado, licensed under GPL-3.0.

[Chinese](readme.md) | **English**

[LightNovelReader (LNR)](https://github.com/dmzz-yyhyy/LightNovelReader) plugin that supports directly importing [lyc486 Legado](https://gitee.com/lyc486/legado) JSON book source files without any format conversion.

**Sole success criterion**: All features of lyc486 Legado book sources must be fully functional in LNR.

---

## Features

| Feature | Status | Description |
|---------|--------|-------------|
| Search | ✅ | Search novels across all imported sources |
| Explore | ✅ | Browse sources with category switching |
| Book Info | ✅ | Title, author, cover, description, tags |
| Chapter List | ✅ | Get chapter listings |
| Content Parsing | ✅ | CSS / XPath / JSONPath / Regex / JS |
| loginUi | ✅ | Full Legado multi-function panel support |
| Feature Toggles | ✅ | Per-source isolated storage, real-time effect |
| Source Management | ✅ | Import / Delete / Enable / Disable |
| JavaScript | ✅ | Rhino 1.8.1 + security sandbox |
| Review Rules | ✅ | ReviewRule data layer ready |

---

## Architecture

Engine core parsing logic has **zero coupling** with Android platform. Platform dependencies injected via SPI interfaces.

## Dependencies

Strictly aligned with lyc486 Legado: Rhino 1.8.1, Jsoup 1.16.2, OkHttp 5.3.2, json-path 2.10.0, Gson 2.13.2, JsoupXpath 2.5.3, commons-text 1.13.1.

## Build

```bash
.\\gradlew :plugin:assembleDebug
```

Output: `plugin/build/outputs/apk/debug/plugin-debug.apk.lnrp`

## License

GNU General Public License v3.0 (GPL-3.0). Derivative of [lyc486 Legado](https://gitee.com/lyc486/legado).

## Acknowledgments

- [Legado](https://gitee.com/lyc486/legado) by lyc486
- [LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader)
- [LNR Plugin Template](https://github.com/dmzz-yyhyy/LightNovelReaderPlguin-Template)
