# 空间锚定显示（Spatial Anchor Display）

Android 原生应用（Kotlin），实现「空间锚定显示」：开启后屏幕内容锚定在真实物理空间中保持固定朝向，旋转手机时画面做反向补偿变换，手机如同一个观察窗口，旋转产生的空余区域填充纯黑。

## 功能特性

- **无主界面**：启动即透明 Activity → 自动申请悬浮窗权限 → 启动前台服务 → 自毁，桌面仅保留悬浮控制按钮
- **悬浮控制按钮**：半透明白色圆形（48dp，透明度 50%），可自由拖拽、松手吸附屏幕左右边缘；点击切换模式
- **空间锚定模式**：
  - `MediaProjection` 实时采集系统全屏画面（桌面 + 所有前台应用）作为渲染源纹理
  - `TYPE_ROTATION_VECTOR` 旋转矢量传感器（融合陀螺/加速度计/磁力计，无漂移），`SENSOR_DELAY_GAME` 采样
  - 模式启动瞬间记录基准姿态，实时解算相对旋转量
  - OpenGL ES 2.0 全屏悬浮渲染（TextureView + EGL window surface），逆三维旋转变换锚定画面，正交投影无透视变形，空余区域纯黑
  - 仅处理旋转姿态，不响应位移、不改变纹理缩放
- **后台保活**：前台服务（`mediaProjection` 类型）常驻通知栏
- **资源释放**：退出模式按逆序释放渲染线程 / 悬浮层 / 虚拟显示 / 传感器 / MediaProjection，无内存泄漏

## 技术规格

| 项 | 值 |
|---|---|
| 语言 | Kotlin |
| minSdk | 29（Android 10） |
| targetSdk / compileSdk | 34 |
| 渲染 | OpenGL ES 2.0（EGL14 + GLES20） |
| 权限 | `SYSTEM_ALERT_WINDOW`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PROJECTION`、`POST_NOTIFICATIONS` |

## 项目结构

```
app/src/main/java/com/example/spatialanchor/
├── MainActivity.kt                  # 透明启动 Activity（权限申请 → 启动服务 → 自毁）
├── MediaProjectionRequestActivity.kt# 录屏授权对话框宿主
├── AnchorService.kt                 # 前台服务：悬浮窗管理、模式状态机、全生命周期
├── FloatingButtonView.kt            # 悬浮按钮（拖拽 / 边缘吸附 / 点击切换）
├── OrientationHelper.kt             # 旋转矢量传感器：基准姿态 + 相对旋转解算
├── ScreenCapturer.kt                # MediaProjection + VirtualDisplay + ImageReader
└── GLRenderer.kt                    # OpenGL ES 2.0 渲染：纹理绘制 + 逆旋转 + 黑色填充
```

## 核心变换原理

```
基准姿态 R0 ──┐
              ├─→ 相对旋转 R_rel = R1ᵀ·R0（基准帧画面 → 当前设备帧）
当前姿态 R1 ──┘
              │
全屏四边形(z=0) × 模型矩阵 R_rel → 正交投影（无透视）→ 空余区域 glClear 纯黑
```

屏幕纹理贴于 z=0 平面，乘以 `R_rel` 后在三维空间旋转；渲染层窗口带 `FLAG_SECURE`，不会进入自身录屏画面，杜绝反馈回路。

## 构建

```bash
# 环境：JDK 17、Android SDK 34（ANDROID_HOME 或 local.properties 的 sdk.dir）
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 使用

1. 安装 APK → 点击图标 → 授予「显示在其他应用上层」（悬浮窗）权限 → 桌面出现半透明圆形「启动」按钮；
2. 点击「启动」→ 系统录屏授权 → 确认后进入空间锚定模式（按钮变「停止」），旋转手机体验画面锚定效果；
3. 点击「停止」或通知栏「停止」退出模式。

## 已知限制

- 倾斜手机时画面会随正交投影收窄（固定平面 + 正交相机的几何必然）；仅绕屏幕法线旋转时画面纯 2D 反向补偿、无缩放；
- Android 11+ 后台启动 Activity 限制：悬浮按钮拉起录屏授权框在绝大多数机型可用，个别激进 ROM 可能拦截；
- 依赖设备陀螺仪（无旋转矢量传感器时自动提示退出）；强磁场环境可能瞬时扰动姿态；
- 开启模式时全屏渲染层不可穿透交互，需先「停止」再操作下层应用；
- 延迟为设计目标（传感器 50–60Hz + vsync 渲染 + 最新帧采集 ≈ 30–50ms），建议真机验证。
