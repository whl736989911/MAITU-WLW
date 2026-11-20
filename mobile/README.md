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
### Release APK：签名与本地验证

当前 `android/app/build.gradle.kts` 没有配置 Gradle `signingConfig`；不要把 `flutter build apk --release` 的产物视为已签名。发布签名在构建后显式完成。签名只用发布方已批准、且与现有安装包升级兼容的私有 keystore；不要新建替代密钥，不把 keystore、口令或签名产物放入仓库。

公开仓库没有记录获批的正式 API HTTPS origin。发布前从发布批准记录取得确切 origin；不得猜测域名、使用厂商网站代替或把站点地址写进公开源码。`AppConfig` 只接受 HTTPS origin（无用户信息、查询或片段；路径为空或 `/`）。以下命令只构建，不访问 API：

```bash
cd mobile
read -r -p '发布批准记录中的正式 API HTTPS origin: ' API_ORIGIN
read -r -p '本次批准的 Android build number（必须高于升级测试基线）: ' BUILD_NUMBER
flutter build apk --release --build-number="$BUILD_NUMBER" --dart-define="API_BASE_URL=$API_ORIGIN"

UNSIGNED_APK=build/app/outputs/flutter-apk/app-release.apk
ALIGNED_APK=build/app/outputs/flutter-apk/app-release-aligned.apk
SIGNED_APK=build/app/outputs/flutter-apk/app-release-signed.apk
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?请先设置 Android SDK 路径}}"
BUILD_TOOLS="$ANDROID_SDK_ROOT/build-tools/36.0.0"
read -r -p '仓库外的批准发布 keystore 绝对路径: ' RELEASE_KEYSTORE
read -r -p '批准发布 key alias: ' RELEASE_KEY_ALIAS

"$BUILD_TOOLS/zipalign" -P 16 -f -v 4 "$UNSIGNED_APK" "$ALIGNED_APK"
"$BUILD_TOOLS/zipalign" -c -P 16 -v 4 "$ALIGNED_APK"
"$BUILD_TOOLS/apksigner" sign \
  --ks "$RELEASE_KEYSTORE" --ks-key-alias "$RELEASE_KEY_ALIAS" \
  --ks-pass stdin --key-pass stdin --out "$SIGNED_APK" "$ALIGNED_APK"
"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$SIGNED_APK"
"$BUILD_TOOLS/aapt" dump badging "$SIGNED_APK" | grep '^package:'
```

`apksigner` 通过标准输入分别读取 keystore 和 key 口令；在专用终端交互输入，不把口令放进命令参数、环境变量、脚本或日志。核对 `verify` 成功、证书 SHA-256 指纹与发布批准记录一致，并确认包名为 `com.maitu.fireplatform.mobile`；指纹不符或包名不符即停止。签名后不要再改 APK；`zipalign` 必须先于签名。

仅在专用测试设备或隔离模拟器上做安装/升级验证，不外发测试包、不上传应用商店。先检查 `adb shell pm list packages com.maitu.fireplatform.mobile`；若已存在但无法确认是受控测试版本，停止，不覆盖、不卸载，也不启动它。首次安装只对确认干净的测试目标执行：

```bash
adb install "$SIGNED_APK"
```

升级验证仅对已知的先前测试包执行：包名相同、使用同一获批签名密钥，且本次 `versionCode`（Flutter `--build-number`）更高；保留应用数据验证原位升级：

```bash
adb install -r "$SIGNED_APK"
```

启动验证只能使用隔离测试 API origin 的测试构建，或已阻断外网/API访问的专用测试目标；不得用生产 API、真实账号或真实凭据。安装及原位升级后可用以下命令确认包路径并启动该测试包：

```bash
adb shell pm path com.maitu.fireplatform.mobile
adb shell monkey -p com.maitu.fireplatform.mobile 1
```

此处命令记录的是操作流程，不表示已使用正式密钥、安装/启动应用或完成设备验证。

> 待发布责任人从批准记录补录并核验确切的正式 API HTTPS origin；源码中没有可据以确认的批准值。

## 海康扫码新增

设备页是唯一新增入口。账号需具备 `device:write`、`device-registrations:write`、`units:read`；服务端另行验证全单位范围、位置和厂商授权。相机由 Android 系统申请权限；拒绝时可返回手动输入。扫描只解析机身标签中的序列号（明文、识别的键值或 HTTP(S) URL 中明确的序列号参数）；不打开或请求码内 URL，不根据地址猜序列号。未识别的真实标签请以铭牌核对后手输。

填写设备名称和型号、厂商设备与通信代码、海康云单位 ID、本地安装位置及可选校验码。保存只建立本地草稿；提交海康云需再次确认。未知结果通过已有登记刷新、按序列号只读核对，不自动重新新增。云端接受后需单独确认本地验收；云端接受不等于设备在线或本地验收完成。测试登记时使用隔离后端和测试记录，不使用真实厂商凭据或发送真实外部写入。
