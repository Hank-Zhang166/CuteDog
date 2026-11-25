# 本地中文播报

CuteDog 使用 Kokoro v1.1-zh Int8 和 ONNX Runtime 1.22.0 在 Android 设备生成 24 kHz 单声道音频。首次发声会校验安装包内模型并解包到应用私有目录，后续会话直接打开该文件。合成阶段在设备本地处理回复文字。播放支持按句生成、预先合成下一句、打断和停止。

模型作者说明中文训练录音由 LongMaoData 授权，含 100 位专业数据集说话人。应用将三组公开普通话风格向量按 0.38、0.37、0.25 混合，形成小狗的音色。Kokoro 由 hexgrad 按 Apache-2.0 发布，k2-fsa/sherpa-onnx 提供 ONNX 转换和发音词典。ONNX Runtime 使用 MIT 许可证。完整许可证、第三方声明及来源留在安装包 `assets/dogvoice/`。[原模型说明](https://huggingface.co/hexgrad/Kokoro-82M-v1.1-zh) · [Sherpa 发布说明](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html) · [固定的模型文件](https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_1/tree/1355334be009ccce397060ee639a874137a128fa)

`scripts/prepare-dog-voice.py` 从上述固定版本检查每个输入的 SHA-256，再生成随包音色。模型文件为 114,203,914 字节，SHA-256 为 `05d793700b0b507f760c183c0e39bdd6a97b43d32fdc7bf7b54a5179f1b11222`。构建所需的完整风格库只在开发机缓存。语音打包配置在 `scripts/pet-speech.gradle`。
