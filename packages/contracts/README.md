# 录音传输协议 v1

手机向声明 koememo_audio_v1 能力的手表打开 /koememo/audio/v1 全双工通道。两端共享 audio/src 的字段校验和序列化。整数使用 Java DataStream 大端编码，字符串使用 writeUTF/readUTF。

手表发送清单条数及不可变片段的会话、文件名、字节数和 SHA-256。手机逐个发送清单下标与已接收偏移，手表返回剩余字节。手机验证落盘文件后回传哈希，手表持久化接收确认。已存在且校验一致的文件只交换确认。手机以 -1 结束，手表返回 true。断线后重新获取清单；索引仅在单次连接内有效。协议不兼容变更应启用新路径和能力名。

支持 koememo_audio_sessions 的手表同时接受 /koememo/audio/v1/{UUID}。手机为每次用户发起的同步生成 UUID，重试沿用该值，便于按会话关联两端 KoeMemoSync 日志。旧版使用原路径和相同的音频协议。
