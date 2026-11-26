# 旧版的本地 A 声线

本页对应 preview.4。维护者可以用 `-PcuteDogBundleLegacySpeech=true` 构建带本地听写和 A 声线的版本。preview.5 默认采用豆包实时语音。[当前语音用法](interaction-review.md)

安装 CuteDog APK 后，长按互动菜单里的麦克风，说完松手。手机完成普通话听写，已配置的 DeepSeek 服务根据文字生成回答，小狗再用 A 声线朗读。朗读与听写可在手机上离线运行。桌面聊天小窗与「陪伴」页都会保留本轮转录和回复。

A 声线来自审阅通过的小萨摩耶原创合成问候。制作时已将这句问候编码为说话人特征、声学编码和转录，三者共约 5.4 KB。手机固定使用这份参考，可朗读实时生成的新句子。仓库留存了原声供维护时复现。[A 声线原始参考](../art/voice/a-reference.flac)

## APK 中的语音资源

移动端使用 Qwen3-TTS 0.6B Base 和 12 Hz 解码器的 Q4_K_M 权重。裁剪移除了录入新音色所需的 221 个张量，共节省 101,303,968 字节。用同一随机种子分别合成一条新短句和一条较长的真实聊天回复，裁剪前后的 24 kHz PCM 逐采样相同。模型直接从已签名 APK 的资源区读取，安装后即可使用。支持 64 位 ARM 与 x86 Android，首句生成速度随处理器和可用运行内存变化。

| 包内文件 | 字节 | SHA-256 |
| --- | ---: | --- |
| a-voice/talker.gguf | 604,877,696 | `68b0c1b091f668552f6c069a74a8fb5c1a73fd58ce3326f43e90e7c259338371` |
| a-voice/codec.gguf | 177,698,144 | `2940ea17c13966f18da4fd0b455e4924b0bebc349ac42a08545086c2e0635248` |

为缩短第一次开口的等待，制作时已把解码器读完 A 声线参考后的状态压缩为 3,960,373 字节的 `samoyed-voice/A.cda`。它只保存这段固定参考的计算结果。手机可把它直接用于任何新回复，并在按下麦克风时提前加载模型。发音引擎按 CPU 支持的指令选择相应实现，生成一段波形后即可交给播放器。[参考状态生成与校验源码](../app/src/main/cpp/a_codec_state.cpp)

## 复现构建

Gradle 在构建机上获取固定修订和 SHA-256 的上游文件，再生成包内权重。仓库仅跟踪固定 A 声线参考、裁剪脚本和许可证。模型使用 Apache-2.0 许可。[模型准备脚本](../scripts/prepare-embedded-voice.py) · [量化权重修订](https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/tree/b7ee2e8c7459c3bea99da23e3d178125a7d1713c) · [Qwen 官方模型](https://huggingface.co/Qwen/Qwen3-TTS-12Hz-0.6B-Base) · [移动端引擎构建配置](../app/src/main/cpp/CMakeLists.txt)

维护者可在小端 Linux 构建机上以 `CUTEDOG_EXPORT_A_STATE` 编译上述 C++ 源文件，链接同一修订的 qwen-core、ggml CPU 和 zlib。依次传入裁剪后的 `codec.gguf`、随包的 `A.rvq` 和输出路径 `A.cda`，即可从完整 56 帧参考重新计算该状态。发布文件 SHA-256 为 `029b4f813b39a0dd09a00df3787220650854c72f2e7bf31db073f474583c1ba9`。
