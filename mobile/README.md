# 消防物联 Android 客户端

移动端直接使用平台现有的客户 API。设备页显示同一授权范围内的旧来源和海康设备；设备详情只按服务端状态和来源绑定展示，不用上报时间推算在线或恢复。警情人工复核提交当前版本，处置状态与设备物理状态分开。
状态未确认与已确认离线分开筛选；以服务端提供的业务状态为准，连接未确认不掩盖已有火警。

## 构建及本地验证

需要 Flutter 3.50、JDK 17 或 21 和 Android SDK（平台 34、35、36、Build Tools 36、NDK 28.2.13676358、CMake 3.22.1，并接受 Android SDK 许可）。Gradle Wrapper 锁定 Gradle 9.9.2 并校验分发包，项目使用 Android Gradle Plugin 9.3.3。请勿把 SDK、签名密钥、构建包、后台地址或隔离账号凭据放入源码。

```sh
cd mobile
flutter pub get --enforce-lockfile
flutter analyze
flutter test test/qr_parser_test.dart
flutter build apk --debug --dart-define=API_BASE_URL=http://10.0.2.2:43204
flutter run -d "$ANDROID_SERIAL" --dart-define=API_BASE_URL=http://10.0.2.2:43204
```

离线构建前先用同一工具链缓存 Gradle 分发包、Flutter/Dart 和 Android 依赖。断网后用 `flutter pub get --offline --enforce-lockfile` 恢复已锁定的 Dart 依赖，再用上述 APK 命令加 `--no-pub` 构建。

`10.0.2.2` 是 Android 模拟器的宿主回环映射；用 USB 调试时也可执行 `adb reverse tcp:43204 tcp:43204`，并改用 `http://127.0.0.1:43204`。地址是平台 origin，不带 `/api`。只有调试构建认可回环或模拟器宿主 HTTP，其他构建要求 HTTPS。发布需以实际发布方的 Android 签名配置打包，不使用调试签名。

## 海康扫码新增

设备页是唯一新增入口。账号需具备 `device:write`、`device-registrations:write`、`units:read`；服务端另行验证全单位范围、位置和厂商授权。相机由 Android 系统申请权限；拒绝时可返回手动输入。扫描只解析机身标签中的序列号（明文、识别的键值或 HTTP(S) URL 中明确的序列号参数）；不打开或请求码内 URL，不根据地址猜序列号。未识别的真实标签请以铭牌核对后手输。

填写设备名称和型号、厂商设备与通信代码、海康云单位 ID、本地安装位置及可选校验码。保存只建立本地草稿；提交海康云需再次确认。未知结果通过已有登记刷新、按序列号只读核对，不自动重新新增。云端接受后需单独确认本地验收；云端接受不等于设备在线或本地验收完成。测试登记时使用隔离后端和测试记录，不使用真实厂商凭据或发送真实外部写入。
