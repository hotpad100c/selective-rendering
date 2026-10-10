# 架构说明

面向要改代码的人。看完这篇应该能回答："我要改的这个行为，落在哪个文件、哪些注入点要各写一遍"。

---

## 一、源码布局

项目用 Stonecutter 管多版本：**只有一份源码 `src/`**，版本差异用 `//? if` 条件解决
（机制与命令见 `docs/development.md` 第一节）。

```
src/
└── client/java/com/selectiverendering/
    ├── SelectiveRendering.java          入口
    ├── SelectiveRenderingManager.java   ★ 状态中心：全部可变状态 + 设置项 + 快照发布 + 落盘
    ├── RenderConfig.java                不可变快照（渲染线程只读它）；用 Builder 构造，加字段只改 Builder / 本类 / publish()
    ├── render/                          方块 / 区块渲染的判定与调度
    │   ├── Alpha.java                   ★ 判定层：getAlpha()/alphaNow()…"这个方块淡不淡"
    │   ├── RebuildScheduler.java        改完之后重烘焙哪些 section（三档）
    │   ├── HiddenSections.java          "哪些 section 含隐藏方块"的记账本
    │   ├── MovingBlockRenderContext.java 活塞移动中渲染的线程上下文
    │   └── BlockRenderContextAccess.java Sodium 方块上下文里挂的 alpha 字段
    ├── blockentity/                     方块实体 / feature 渲染淡化
    │   ├── TranslucentSubmitForwarder.java   ★ 换半透明 RenderType 的提交转发（方法清单唯一一份）
    │   ├── SelectiveSubmitNodeCollector.java 方块实体淡化入口（继承上面那个）
    │   ├── AlphaVertexConsumer.java     给顶点乘 alpha 的 VertexConsumer 包装
    │   ├── BufferSourceHooks.java       取 VertexConsumer 时的挂钩
    │   ├── HiddenRenderTypes.java       本帧需要套 alpha 的 RenderType 标记
    │   └── TranslucentRenderTypes.java  为一个 RenderType 找半透明变体
    ├── lighting/
    │   └── LightSuppression.java        ★ 光照：伪装(airIfHidden) + 标记 + 分批重算
    ├── region/                          选区与魔杖
    │   ├── Region.java                  选区几何（含 subtract 挖洞）；相等性只看范围，附带 RegionOptions
    │   ├── RegionOptions.java           选区自己的隐藏对象开关（方块/实体/粒子）和本地方块名单
    │   ├── RegionEditor.java            魔杖双角点 + 选区的增/删/裁/点选
    │   ├── RegionMerger.java            散点坐标 → 长方体（贪心合并）
    │   ├── CameraRay.java               ★ 魔杖射线：从渲染相机出发、穿过鼠标指针
    │   └── WandHud.java                 手持魔杖时的 HUD（由 GuiMixin 转发调用）
    ├── matchingrule/                    方块名单规则
    │   ├── BlockMatchRule.java          规则字符串的解析与匹配
    │   └── BlockListMessage.java        增删规则时屏幕下方的浮动提示
    ├── interaction/
    │   └── FadedBlockGetter.java        ★ 客户端射线专用：把淡化方块说成空气的 BlockGetter 外壳
    ├── recording/
    │   └── BlockChangeRecorder.java     变化记录器
    ├── config/                          配置：磁盘配置、预设、热键、MaLiLib 界面与接线
    │   ├── ModConfig.java / Preset.java 磁盘配置 / 预设
    │   ├── ModHotkeys.java              修饰键查询
    │   └── ModConfigs.java 等           MaLiLib 配置界面
    ├── compat/Platform.java             ★ 跨版本适配层
    ├── util/                            Log、BlockPosScratch
    └── mixin/                           补丁，按功能分包，与上面的包一一对应
        ├── render/
        │   ├── ModelBlockRendererMixin  ★ 方块：取消 / 面剔除 / alpha / 换材质
        │   ├── SectionCompilerMixin     可见性图不再把隐藏方块当不透明
        │   ├── FluidRendererMixin       流体：alpha / 面剔除
        │   └── FluidModelMixin          流体整体挪到半透明层
        ├── blockentity/
        │   ├── BlockEntityRenderDispatcherMixin  方块实体淡化
        │   ├── BlockFeatureRendererMixin 活塞移动中的方块
        │   ├── SubmitNodeCollectionMixin 移动方块进半透明相位
        │   ├── FeatureRenderDispatcherMixin 每帧清 HiddenRenderTypes 标记
        │   ├── RenderTypeFeatureRendererMixin / BufferSourceMixin  取 VertexConsumer 的入口（按版本）
        │   └── RenderTypeAccessor / RenderSetupAccessor / TextureBindingAccessor
        ├── lighting/
        │   ├── LightEngineMixin         光照引擎把隐藏方块当空气（→ LightSuppression）
        │   ├── AreaLightPacketMixin     服务端光照到达时标记区块（→ LightSuppression.markChunk）
        │   ├── AreaLightFlushMixin      runLightUpdates 之后消化待重算区块（→ LightSuppression.flush）
        │   ├── BlockModelLighterMixin   平滑光照(AO)把隐藏方块当空气
        │   └── LightmapRenderStateExtractorMixin
        ├── region/
        │   ├── MouseHandlerMixin        ★ 魔杖的全部鼠标交互
        │   ├── LevelRendererMixin       选区线框（双注入点，兼容方法改名）
        │   └── GuiMixin                 HUD 注入点（→ WandHud）
        ├── interaction/ClientLevelClipMixin  ★ 客户端射线：淡化方块当空气（见第六节）
        ├── recording/ClientLevelMixin   给记录器喂方块变化
        └── compat/                      第三方 Mod 补丁：sodium/、indigo/（Fabric Renderer API）、entityCulling/、ImmediatelyFast/
```

按版本取舍的文件（内部用 `//? if` 区分，没有"另存一份"）：

| 文件 | 为什么随版本变 |
|---|---|
| `compat/Platform.java` | ★ 跨版本适配层：鼠标键码、重建入口、GUI 访问、按键名、`MaterialInfo` 构造参数 |
| `blockentity/AlphaVertexConsumer.java` | 实现 `VertexConsumer`，接口成员随版本增删 |
| `blockentity/TranslucentSubmitForwarder.java` | 实现 `OrderedSubmitNodeCollector`，方法集随版本变；**唯一一份**方法清单（换半透明 RenderType 的那几个也在这里） |
| `blockentity/SelectiveSubmitNodeCollector.java` | 方块实体淡化的入口，继承上面那个类，只管 `target()` / `order(n)` |
| `mixin/blockentity/BlockFeatureRendererMixin` 等 | 注入目标类 / 方法随版本变 |

> `src/client/resources/selective-rendering.client.mixins.json` **只有一份，是纯 JSON，不含任何条件**。
> 只在部分版本存在的 mixin（`blockentity.BufferSourceMixin`、`compat.ImmediatelyFast.ImmediatelyFastBufferSourceMixin`、
> `blockentity.RenderTypeFeatureRendererMixin`）在 json 里始终列出；目标不存在的版本里，
> 源文件用 `//? if` 把类换成 dummy mixin：`@Mixin(Minecraft.class)` + 空类体，不注入任何方法。

---

## 二、核心判定 `Alpha.getAlpha(state, pos, moving)`

全 Mod 只有这一个"真理来源"，返回：

| 返回值   | 含义                                              |
| -------- | ------------------------------------------------- |
| `-1`     | 正常渲染                                          |
| `0`      | 完全隐藏（透明度 100%），mixin 直接 `ci.cancel()` |
| `1..255` | 顶点 alpha，保留几何但改颜色 + 挪到半透明层       |

它被区块构建线程、光照线程、渲染线程**并发**调用，因此只读不可变快照、全程不加锁。

两个变体：

- **`getAlpha(...)`**：命中时会往 `HiddenSections` 记账（用于改透明度时增量重建）。
  只有真正参与画面渲染的路径用它。
- **`alphaNow(...)`**：只判定、不记账。光照 / AO 用它在光照线程上以百万次量级调用，
  不该有副作用。对外入口是 `airIfHidden(state, pos)`。

### 光照是怎么跟着隐藏集合走的

```
隐藏集合变了 ─► LightSuppression.relight()  ─┐
(切模式/增删裁选区)                            ├─► PENDING 区块集合 ─► flush()：每帧限额 checkBlock
服务端光照包到达 ─► markChunk()  ────────────┘     ▲
                                  AreaLightFlushMixin 挂在 runLightUpdates RETURN
```

- 伪装：`LightSuppression.airIfHidden` 被光照引擎 / 三套 AO 的 mixin 调用；
- 服务端发来的光照是按"方块真实存在"算的，会覆盖客户端的伪装，所以到达后要补算；
- 只对 **选区（外扩 1 格）所在的列** 发 `checkBlock`，扩散交给引擎，不再整块外扩 15 格暴力扫。

### 修改状态的标准姿势

```java
synchronized (LOCK) {
    ...改 RULES / REGIONS / PRESETS / 各标量字段...
    commit();              // 或 commitAndRemember()
}
...在锁外做重建 / 重算光照等耗时操作...
```

`commit()` = `publish() + persist()`，`commitAndRemember()` 再多一步记住用户输入的原始文本
（防止配置界面回灌触发回调的死循环）。**不要**再单独调 `publish()` / `persist()`。

---

## 三、渲染管线的注入点地图

同一个逻辑要打 3~4 份补丁，因为原版 / Sodium / Indigo 各有各的数据结构：

| 目标             | 原版                                                                | Sodium                                | Indigo                                       |
| ---------------- | ------------------------------------------------------------------- | ------------------------------------- | -------------------------------------------- |
| 方块几何 + alpha | `ModelBlockRendererMixin`                                           | `sodium.BlockRendererMixin`           | `compat.FabricRendererApiBlockRendererMixin` |
| 可见性图         | `SectionCompilerMixin`                                              | `sodium.ChunkBuilderMeshingTaskMixin` | —                                            |
| 平滑光照当空气   | `BlockModelLighterMixin`                                            | `sodium.LightDataAccessMixin`         | `compat.FabricRendererApiLightMixin`         |
| 光照引擎当空气   | `LightEngineMixin`                                                  | （共用）                              | （共用）                                     |
| 流体             | `FluidRendererMixin` + `FluidModelMixin`                            | `sodium.DefaultFluidRendererMixin`    | —                                            |
| 移动方块         | `BlockFeatureRendererMixin`                                         | —                                     | `compat.FabricRendererApiMovingBlockMixin`   |
| 方块实体         | `BlockEntityRenderDispatcherMixin` → `SelectiveSubmitNodeCollector` | —                                     | —                                            |

后三项（光照引擎 / 方块实体）三条渲染后端是共用的，因为那里走的还是原版类。

---

## 四、跨版本适配的边界

### 随版本变的文件（用 `//? if` 取舍）

| 文件 | 原因 |
| --- | --- |
| `compat/Platform.java` | **每个方法在三个版本里都不一样**：鼠标键码 `0/1` vs `1/3`、`levelRenderer` vs `levelExtractor`、`minecraft.screen` vs `minecraft.gui.screen()`、`KEYSYM` vs `KEYBOARD`、`MaterialInfo` 构造参数个数。这就是它的职责。 |
| `blockentity/AlphaVertexConsumer.java` | 实现 `VertexConsumer` 接口，接口成员随版本增删（26.3 多一个 `setUv3`）。 |
| `blockentity/TranslucentSubmitForwarder.java` | 实现 `OrderedSubmitNodeCollector` 接口，方法集随版本变；`SelectiveSubmitNodeCollector` 继承它。 |
| `mixin/blockentity/BlockFeatureRendererMixin.java` | 26.1.2 打 `BlockFeatureRenderer.renderMovingBlockSubmits`；26.2+ 打 `MovingBlockFeatureRenderer.buildGroup`。目标类和注入策略都不是一回事。 |
| `mixin/blockentity/RenderTypeFeatureRendererMixin`<br>`mixin/blockentity/BufferSourceMixin`<br>`mixin/compat/ImmediatelyFastBufferSourceMixin` | 三个"取 VertexConsumer"的入口各自依赖**只在部分版本存在**的类：`MultiBufferSource` 26.3 已删除；`RenderTypeFeatureRenderer` 26.2+ 才有。整文件包进条件里，不满足的版本是空文件。 |
| `mixin/blockentity/FeatureRenderDispatcherMixin` | 差异不只是方法名，**返回类型也不同**：26.1.2 `endFrame` 返回 `void`，26.2+ `prepareFrame` 返回 `PreparedFrame`（26.1.2 里没有这个类）。 |
| `mixin/region/GuiMixin` | 26.1.2 打 `Gui`，26.2+ 打 `Hud`（26.1.2 里没有这个类）；正文在共享的 `WandHud`。 |

### 已经在共享里的"跨版本"技巧

- **`LevelRendererMixin`**：方法改名过（`collectPerFrameGizmos` → `collectPerFrameRenderThreadGizmos`），
  参数签名相同，于是写两个 `require = 0` 的 `@Inject` 委托给同一个 `emit()`。
- **`WandHud`**：HUD 正文各版本相同，只有 mixin 目标类不同（26.1.2 是 `Gui`，26.2+ 是 `Hud`）。
  正文抽到共享类，`GuiMixin` 只剩转发和一个按版本切换的 `@Mixin` 目标。
- **可选依赖的 mixin**（Sodium / Indigo / ImmediatelyFast）统一用
  `require = 0` + `remap = false` + 字符串 `targets`，缺席时静默跳过。

### ⚠️ `ImmediatelyFastBufferSourceMixin` 是活代码，别当成历史包袱删掉

对照 ImmediatelyFast 的各分支可以确认它**只对 26.1.2 有效、且正好必要**：

| IF 分支                           | 目标 MC                    | `BatchableBufferSource`                                                                             |
| --------------------------------- | -------------------------- | --------------------------------------------------------------------------------------------------- |
| `origin/26.1`（`1.15.4+26.1`）    | **26.1 / 26.1.1 / 26.1.2** | ✅ 有（`MixinRenderBuffers` 用 `@Redirect` 把某个 `BufferSource` 换成它，且**覆写了 `getBuffer`**） |
| `origin/26.2` 起（`1.17.2+26.3`） | 26.2+                      | ❌ 已删除，批处理改成只把 `RenderTypeFeatureRenderer$Group` 的 `canReorder` 改成 true               |

也就是说：

- 26.1.2 + IF 1.15.x：IF 的子类覆写了 `getBuffer`，虚分派轮不到我们注入在父类方法体里的代码，
  少了这个补丁 → **被淡化的方块实体和移动中的方块完全不淡化**（地形不受影响，那条路走
  `ModelBlockRendererMixin`），且不报错，很难排查；
- 26.2 / 26.3：IF 已经不动 `BufferSource`，取 buffer 的入口本来就是
  `RenderTypeFeatureRenderer.getVertexBuilder`，**天然不需要补丁**，所以那两个版本里没有这个文件是对的。

顺带记录另一种可行思路（Lucidity 就是这么做的，见 `ControllableTransparentBuffersWrapper`）：
不注入 `getBuffer`，而是在调用点把整个 `BufferSource` 对象包一层壳再传给渲染器。
好处是天然免疫"provider 被换实现"，代价是要枚举所有渲染调用点（BE dispatcher / Sodium 的
`renderBlockEntity` / 粒子 / FeatureRenderDispatcher…）。我们选的是"枚举 provider 实现类"这条路。

⚠️ 用"两个 `require = 0` 注入点兼容改名"这种写法时，要假设**将来某天两个方法名同时存在**，
那时逻辑会被执行两次。`LevelRendererMixin` 已在注释里标明（后果只是描边变粗）。

---

## 五、配置层（MaLiLib）

`config/ModConfigs.java` 是接线中心，注意它的**防回环机制**：

把 manager 的值回灌到控件（`syncFromManager()`）会触发控件的 `setValueChangeCallback`，
回调又会去改 manager。所以用一个 `syncing` 标志在回灌期间屏蔽回调（见 `write(...)`）。
**新增配置项时务必照这个模式接线**，否则改一次会触发两次重建。

新增配置项：在 `ModConfigs` 里 `new ConfigXxx(name, ...).apply(KEY)` 并加进 `OPTIONS`（界面按这个顺序列出），
然后在 `lang/*.json` 里补 `selective_rendering.config.name.<name>` / `.comment.<name>`
（快捷键类再补 `.prettyName.<name>`）。翻译走 Minecraft 标准语言文件，由 Fabric API 的 resource loader 加载（MaLiLib 本身就依赖它）。

### 选区行（可展开）

`ModConfigGui` 在全局配置项之后列出每个选区：`RegionRowConfig` 是个假配置项，由 `WidgetRegionRow` 画出
展开按钮 / 范围 / 删除；展开后追加两行角点（只读标签）、一行 `WidgetRegionTargets`（方块 / 实体 / 粒子，
每项后跟一个方形小按钮）和一个本地 `ConfigStringList`。展开状态按几何范围记在 `ModConfigGui.EXPANDED`；
标题行在 `render` 里登记外框，由列表控件的 `drawContents` 统一画在最上层。
改动经 `RegionEditor.updateRegion` 替换 `REGIONS` 里的对应项，并触发重建和光照重算。

- 方块判定只用 `blocks` 为真的选区（`RenderConfig.blockRegions`）；本地名单与全局名单取并集，
  只在选区内部且模式用到名单时生效（`Alpha.listedLocally`）。
- `entities` / `particles` 目前只存盘和显示，没有实际效果。
- 预设里的选区仍是纯范围字符串，应用预设会恢复默认开关。

配置项本身只是"视图"，权威数据源永远是 `SelectiveRenderingManager`；
落盘由 `ModConfig.save()` 防抖异步完成。

---
