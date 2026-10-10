package com.selectiverendering;

import com.selectiverendering.config.ModConfig;
import com.selectiverendering.config.Preset;
import com.selectiverendering.lighting.LightSuppression;
import com.selectiverendering.matchingrule.BlockMatchRule;
import com.selectiverendering.recording.BlockChangeRecorder;
import com.selectiverendering.region.Region;
import com.selectiverendering.region.RegionEditor;
import com.selectiverendering.render.Alpha;
import com.selectiverendering.render.HiddenSections;
import com.selectiverendering.render.RebuildScheduler;
import com.selectiverendering.util.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * 状态中心：模式 / 透明度 / 方块名单 / 选区 / 预设等<b>全部可变状态</b>的唯一持有者，
 * 负责修改、发布快照、落盘。
 *
 * <h2>谁管什么</h2>
 * <pre>
 *   配置界面(ModConfigs)  ─┐
 *   魔杖交互(MouseHandler)─┼─► 本类 / RegionEditor ──► RebuildScheduler ──► LightSuppression
 *   记录器(BlockChangeRecorder)─┘   (写状态+落盘)        (重烘焙 section)       (重算光照)
 *
 *   渲染管线(mixin 们) ─► Alpha（只读判定，读本类发布的 RenderConfig 快照）
 * </pre>
 * <ul>
 *   <li>{@link Alpha}：只读判定"这个方块该不该淡化、alpha 多少"，热路径；</li>
 *   <li>{@link RegionEditor}：魔杖双角点、已存选区的增 / 删 / 裁 / 点选；</li>
 *   <li>{@link RebuildScheduler}：改完之后重建哪些 section；</li>
 *   <li>{@link LightSuppression}：光照伪装与重算；</li>
 *   <li>本类：其余的设置项（模式、透明度、名单、预设……）与状态本身。</li>
 * </ul>
 *
 * <h2>两套数据：可变集合 + 不可变快照</h2>
 * <ol>
 *   <li><b>可变集合</b>：{@link #RULES} / {@link #RECORDED} / {@link #REGIONS} / {@link #PRESETS}
 *       以及 mode/transparency/... 这些字段，是真正被修改的状态。
 *       修改时必须持 {@link #LOCK}。{@code RegionEditor} 也直接改其中的选区与角点（包内可见）。</li>
 *   <li><b>不可变快照</b>：修改完调用 {@link #publish()}，把所有状态<b>一次性</b>打包成一个
 *       {@link RenderConfig} 记录，写进单个 {@code volatile} 字段 {@link #config}。
 *       渲染线程只读 {@code config}，因此遍历时不需要加锁。
 *       <b>代价：所有写操作后都必须记得调用 {@code commit()}，漏掉就会出现"改了但画面没变"。</b></li>
 * </ol>
 * 每线程的判定缓存挂在快照上（见 {@link Alpha}），换快照即自动失效。
 *
 * <p>为什么快照必须是一个对象而不是多个 {@code volatile} 字段：以前 mode / transparency /
 * invert / 名单 / 选区是 5 个独立的 volatile，而 {@code setMode} 是先赋 {@code mode}、
 * 再 {@code publish()} 刷新名单的；这中间存在一个窗口，区块构建线程会读到
 * <b>新 mode + 旧名单</b>，产出一个混淆两代配置的网格（后续 {@link RebuildScheduler#rebuildChunks()} 会自愈，
 * 但那几帧的画面是错的）。打包成一条引用后，读到的永远是一份自洽的配置。
 *
 * <h2>修改状态的标准姿势</h2>
 * <pre>
 *   synchronized (LOCK) {
 *       ...改 RULES / REGIONS / PRESETS / 各标量字段...
 *       commit();              // 或 commitAndRemember()
 *   }
 *   ...在锁外做重建 / 重算光照等耗时操作...
 * </pre>
 * {@link #commit()} 与 {@link #commitAndRemember()} 内部会做
 * {@code publish() + persist()}，并要求调用者已持有 {@link #LOCK}。
 * <b>不要</b>再单独调用 {@code publish()} / {@code persist()}。
 */
public final class SelectiveRenderingManager {
	public static final int TRANSPARENCY_STEP = 1;
	public static final int MAX_TRANSPARENCY = 100;

	public static final Mode DEFAULT_MODE = Mode.OFF;
	public static final int DEFAULT_TRANSPARENCY = 50;
	public static final String DEFAULT_WAND = "minecraft:breeze_rod";
	public static final boolean DEFAULT_FULL_BRIGHT = true;

	/**
	 * 渲染模式。名字的读法是「<b>范围</b> + <b>名单条件</b>」：
	 * 范围决定"在哪儿"，名单条件决定"哪种方块"，两者同时满足才被淡化。
	 *
	 * <p>对照表（"隐藏"= 被淡化/半透明）：
	 * <pre>
	 *   OFF                     不隐藏任何东西（功能关闭）
	 *   REGION_INSIDE           选区内全部隐藏
	 *   REGION_INSIDE_LISTED    选区内 且 在名单里的
	 *   REGION_INSIDE_UNLISTED  选区内 且 不在名单里的
	 *   REGION_OUTSIDE          选区外全部隐藏
	 *   REGION_OUTSIDE_LISTED   选区外 且 在名单里的
	 *   REGION_OUTSIDE_UNLISTED 选区外 且 不在名单里的
	 *   BLACKLIST               全局，名单里的隐藏
	 *   WHITELIST               全局，名单外的隐藏（只剩名单里的可见）
	 * </pre>
	 *
	 * <p>枚举顺序 = 滚轮循环顺序 = 配置下拉框顺序，不要随意调整。
	 */
	public enum Mode {
		OFF("off"),
		REGION_INSIDE_LISTED("region_inside_listed"),
		REGION_INSIDE_UNLISTED("region_inside_unlisted"),
		REGION_INSIDE("region_inside"),
		REGION_OUTSIDE_LISTED("region_outside_listed"),
		REGION_OUTSIDE_UNLISTED("region_outside_unlisted"),
		REGION_OUTSIDE("region_outside"),
		BLACKLIST("blacklist"),
		WHITELIST("whitelist");

		private final String key;

		Mode(String key) {
			this.key = key;
		}

		public Component displayName() {
			return Component.translatable(SelectiveRendering.MOD_ID + ".mode." + key);
		}

		public Mode cycle(int direction) {
			Mode[] values = values();
			return values[Math.floorMod(ordinal() + direction, values.length)];
		}

		public boolean usesRegion() {
			return this != OFF && this != BLACKLIST && this != WHITELIST;
		}

		public boolean usesList() {
			return this != OFF && this != REGION_INSIDE && this != REGION_OUTSIDE;
		}

		/**
		 * "名单里的是被隐藏的那一批吗？"
		 *
		 * <p>用于 {@link RebuildScheduler#rebuildAfterListChange}：往名单里加一条规则时，
		 * 如果本模式隐藏的正是名单里的方块（黑名单语义），那新增的那一个方块
		 * 从可见变隐藏，可以只重建它所在的 section；
		 * 反之（白名单语义）新增规则意味着名单外的方块……其实没有变化，
		 * 只有"删除规则"才会让方块从隐藏变可见。
		 */
		public boolean hidesListed() {
			return this == BLACKLIST || this == REGION_INSIDE_LISTED || this == REGION_OUTSIDE_LISTED;
		}
	}

	public static final Object LOCK = new Object();
	private static final List<BlockMatchRule> RULES = new ArrayList<>();

	private static final List<BlockMatchRule> RECORDED = new ArrayList<>();

	public static final List<Region> REGIONS = new ArrayList<>();

	private static final List<Preset> PRESETS = new ArrayList<>();

	private static volatile List<String> ruleSourceInput = List.of();
	private static volatile List<String> regionSourceInput = List.of();
	private static volatile List<String> recordSourceInput = List.of();

	/**
	 * 记录器（{@link BlockChangeRecorder}）的过滤方式：
	 * 一次"记录"只关心某一类方块的变化。
	 * <ul>
	 *   <li>{@link #LISTED}　只记录<b>名单内</b>方块发生变化的坐标；</li>
	 *   <li>{@link #UNLISTED} 只记录<b>名单外</b>方块发生变化的坐标。</li>
	 * </ul>
	 * 名单是配置里的 {@code recorded}（对应 {@link #RECORDED}），
	 * 和渲染用的 {@link #RULES} 是两份独立的名单。
	 */
	public enum RecordMode {
		LISTED("record_listed"),
		UNLISTED("record_unlisted");

		private final String key;

		RecordMode(String key) {
			this.key = key;
		}

		public Component displayName() {
			return Component.translatable(SelectiveRendering.MOD_ID + ".mode." + key);
		}
	}

	/**
	 * 记录器默认走<b>白名单</b>（{@link RecordMode#LISTED}）：只记录名单里的方块发生变化的位置。
	 *
	 * <p>早期默认是黑名单（记录名单外的所有变化），实际用起来很容易录进一堆无关方块——
	 * 大部分人录的时候心里想的是"我要这一类"，所以默认改成白名单。
	 *
	 * <p>注意白名单 + 空名单会退化成"什么都录不到"，因此
	 * {@link #matchesRecorded} 对空名单做了兜底（当成不过滤），并在开始记录时提示一句。
	 */
	public static final RecordMode DEFAULT_RECORD_MODE = RecordMode.LISTED;

	/**
	 * 录制结束后，记录到的位置要怎么作用到选区列表上。
	 *
	 * <p>和 {@link RecordMode} 是两个正交的维度：
	 * {@code RecordMode} 决定"哪些方块的变化值得记录"（名单内 / 名单外），
	 * 本枚举决定"记录到的位置最终怎么用"。
	 */
	public enum RecordApplyMode {
		/** 新增：把记录到的位置合并成长方体，加进选区列表。 */
		ADD("record_add"),

		/** 裁剪：把记录到的位置从已有选区里挖掉。 */
		SUBTRACT("record_subtract");

		private final String key;

		RecordApplyMode(String key) {
			this.key = key;
		}

		public Component displayName() {
			return Component.translatable(SelectiveRendering.MOD_ID + ".mode." + key);
		}
	}

	public static final RecordApplyMode DEFAULT_RECORD_APPLY_MODE = RecordApplyMode.ADD;

	/**
	 * "反转"是否默认开启。默认关闭：行为与老版本一致（命中的那批被淡化）。
	 *
	 * <p>开启后含义变成"<b>除了</b>命中的那批，其余全部淡化"，
	 * 于是"选区内 + 名单内 + 反转"就是"只显示选区内的指定方块"——
	 * 这是不引入 6 个新模式枚举就能表达"取反"的办法。
	 */
	public static final boolean DEFAULT_INVERT = false;

	/**
	 * "穿透交互"是否默认开启：准星射线把被淡化的方块当空气，
	 * 于是能瞄准、描边、破坏、右键它<b>后面</b>的方块。
	 *
	 * <p>默认开启——这正是本功能的全部意义；关掉就退回"淡化方块照样挡住准星"。
	 */
	public static final boolean DEFAULT_PASS_THROUGH = true;

	// ─────────────────────────────────────────────────────────────────────────
	// 可变状态：只在 synchronized (LOCK) 内读写。
	// 渲染线程一律读下面的 config 快照，不要直接读这些字段。
	// ─────────────────────────────────────────────────────────────────────────

	private static Mode mode = DEFAULT_MODE;
	private static int transparency = DEFAULT_TRANSPARENCY;
	private static RecordMode recordMode = DEFAULT_RECORD_MODE;
	private static RecordApplyMode recordApplyMode = DEFAULT_RECORD_APPLY_MODE;
	private static boolean invert = DEFAULT_INVERT;
	private static String wand = DEFAULT_WAND;
	private static boolean fullBright = DEFAULT_FULL_BRIGHT;
	private static boolean passThrough = DEFAULT_PASS_THROUGH;
	public static BlockPos corner1;
	public static BlockPos corner2;
	private static AABB region;

	/**
	 * 当前生效的配置快照。所有渲染侧读取都走这里。
	 *
	 * <p>它是<b>唯一一个</b>被渲染线程读的可变字段：一次 {@link #publish()} 整体替换，
	 * 因此不会出现"读到一半新一半旧"的情况。
	 */
	private static volatile RenderConfig config = RenderConfig.initial();

	/** 当前快照。包内的判定层 {@link Alpha} 用；包外一律走具体的 getter。 */
	public static RenderConfig config() {
		return config;
	}

	/**
	 * "当前选中的角点"（-1 = 未选中）。
	 *
	 * <p>它<b>不</b>在 {@link RenderConfig} 里，因为 {@link RegionEditor#selectCorner} 刻意改它
	 * 但不落盘也不重建（只是切换线框的高亮），如果放进快照就必须再 publish 一次。
	 * 它只影响 UI，不参与任何渲染判定，独立 volatile 足够。
	 */
	public static volatile int selectedCorner = -1;

	private SelectiveRenderingManager() {
	}

	/**
	 * 从磁盘配置恢复运行时状态。只在 {@link SelectiveRendering#onInitializeClient()} 里调用一次。
	 *
	 * <p>所有字段都在 {@link #LOCK} 内赋值，末尾 {@link #publish()} + {@link #rememberSources()}
	 * 保证快照和"上一次用户输入的文本"同步，避免配置界面一打开就把值写回去。
	 */
	public static void load() {
		ModConfig config = ModConfig.get();

		synchronized (LOCK) {
			RULES.clear();
			RECORDED.clear();
			REGIONS.clear();

			mode = config.mode == null ? Mode.OFF : config.mode;
			transparency = Math.clamp(config.transparency, 0, MAX_TRANSPARENCY);
			recordMode = config.recordMode == null ? DEFAULT_RECORD_MODE : config.recordMode;
			recordApplyMode = config.recordApplyMode == null ? DEFAULT_RECORD_APPLY_MODE : config.recordApplyMode;
			invert = config.invert;
			wand = config.wand == null || config.wand.isBlank() ? DEFAULT_WAND : config.wand.trim();
			fullBright = config.fullBright;
			passThrough = config.passThrough;

			if (config.rules != null) {
				for (String source : config.rules) {
					BlockMatchRule rule = BlockMatchRule.parse(source);
					if (rule != null) {
						RULES.add(rule);
					}
				}
			}

			corner1 = toBlockPos(config.regionPos1);
			corner2 = toBlockPos(config.regionPos2);
			selectedCorner = corner2 != null ? 1 : corner1 != null ? 0 : -1;
			updateRegion();

			if (config.recorded != null) {
				for (String source : config.recorded) {
					BlockMatchRule rule = BlockMatchRule.parse(source);
					if (rule != null) {
						RECORDED.add(rule);
					}
				}
			}

			if (config.regions != null) {
				for (int[] values : config.regions) {
					Region added = toRegion(values);
					if (added != null) {
						REGIONS.add(added);
					}
				}
			}

			if (config.presets != null) {
				for (Preset preset : config.presets) {
					if (preset != null) {
						PRESETS.add(preset);
					}
				}
			}

			publish();
			rememberSources();
		}

		Log.say("[state] loaded: mode {}, transparency {}%, {} rule(s), {} region(s), {} preset(s)", mode, transparency, RULES.size(), REGIONS.size(), PRESETS.size());
	}

	public static Mode getMode() {
		return config.mode();
	}

	/**
	 * 切换模式。代价很大：{@link RebuildScheduler#rebuildChunks()} 会重建<b>全部</b>区块并重算选区光照。
	 * 滚轮连续切换时会明显卡顿，属于预期行为。
	 */
	public static void setMode(Mode newMode) {
		synchronized (LOCK) {
			if (mode == newMode) {
				return;
			}

			mode = newMode;
			commit();
		}

		Log.say("[state] mode {}", newMode.name());
		RebuildScheduler.rebuildChunks();
	}

	public static void cycleMode(int direction) {
		setMode(getMode().cycle(direction));
	}

	public static int getTransparency() {
		return config.transparency();
	}

	/**
	 * 设置透明度（百分比）。100% = alpha 0 = 完全隐藏，0% = alpha 255 = 完全不透明。
	 *
	 * <p>只改顶点 alpha、不改几何，所以不需要重建区块：
	 * {@link RebuildScheduler#rebuildHidden()} 优先只重建"被隐藏过的 section"（见 {@link HiddenSections}），
	 * 只有记账太多时才退化成全量重建。
	 */
	public static void setTransparency(int value) {
		int clamped = Math.clamp(value, 0, MAX_TRANSPARENCY);

		synchronized (LOCK) {
			if (clamped == transparency) {
				return;
			}

			transparency = clamped;
			commit();
		}

		Log.say("[state] transparency {}%", clamped);
		RebuildScheduler.rebuildHidden();
	}

	public static List<Region> regions() {
		return config.regions();
	}

	public static List<String> regionSources() {
		return config.regions().stream().map(Region::toSource).toList();
	}

	public static void setRegionSources(List<String> sources) {
		List<String> wanted = sources == null ? List.of() : List.copyOf(sources);
		if (wanted.equals(regionSourceInput)) {
			return;
		}

		regionSourceInput = wanted;

		synchronized (LOCK) {
			REGIONS.clear();

			if (sources != null) {
				for (String source : sources) {
					Region parsed = Region.parse(source);
					if (parsed != null && !REGIONS.contains(parsed)) {
						REGIONS.add(parsed);
					}
				}
			}

			commit();
		}

		if (getMode().usesRegion()) {
			RebuildScheduler.rebuildChunks();
		}
	}

	public static void updateRegion() {
		if (corner1 == null || corner2 == null) {
			region = null;
			return;
		}

		region = new AABB(
			Math.min(corner1.getX(), corner2.getX()),
			Math.min(corner1.getY(), corner2.getY()),
			Math.min(corner1.getZ(), corner2.getZ()),
			Math.max(corner1.getX(), corner2.getX()) + 1,
			Math.max(corner1.getY(), corner2.getY()) + 1,
			Math.max(corner1.getZ(), corner2.getZ()) + 1
		);
	}

	public static boolean addRule(String source) {
		BlockMatchRule rule = BlockMatchRule.parse(source);
		if (rule == null) {
			return false;
		}

		synchronized (LOCK) {
			for (BlockMatchRule existing : RULES) {
				if (existing.source().equalsIgnoreCase(rule.source())) {
					return false;
				}
			}

			RULES.add(rule);
			commitAndRemember();
		}

		Log.say("[state] rule added: {}", rule.source());
		RebuildScheduler.rebuildAfterListChange(true);
		return true;
	}

	public static boolean removeRule(String source) {
		synchronized (LOCK) {
			if (!RULES.removeIf(rule -> rule.source().equalsIgnoreCase(source.trim()))) {
				return false;
			}

			commitAndRemember();
		}

		Log.say("[state] rule removed: {}", source.trim());
		RebuildScheduler.rebuildAfterListChange(false);
		return true;
	}

	public static List<String> ruleSources() {
		return config.rules().stream().map(BlockMatchRule::source).toList();
	}

	public static void setRuleSources(List<String> sources) {
		List<String> wanted = sources == null ? List.of() : List.copyOf(sources);
		if (wanted.equals(ruleSourceInput)) {
			return;
		}

		ruleSourceInput = wanted;

		synchronized (LOCK) {
			RULES.clear();

			if (sources != null) {
				for (String source : sources) {
					BlockMatchRule rule = BlockMatchRule.parse(source);
					if (rule != null && !hasRule(rule.source())) {
						RULES.add(rule);
					}
				}
			}

			commit();
		}

		if (getMode().usesList()) {
			RebuildScheduler.rebuildChunks();
		}
	}

	public static boolean hasRule(String source) {
		String trimmed = source.trim();
		for (BlockMatchRule rule : RULES) {
			if (rule.source().equalsIgnoreCase(trimmed)) {
				return true;
			}
		}

		return false;
	}

	public static List<String> recordSources() {
		return config.recorded().stream().map(BlockMatchRule::source).toList();
	}

	/**
	 * 配置界面（或 MaLiLib 的"重置"按钮）写入记录名单。
	 *
	 * <p><b>判重必须同时比对"当前真实名单"</b>，不能只看 {@code recordSourceInput}：
	 * 那份缓存只在本方法和 {@link #rememberSources()} 里更新，
	 * 一旦名单被别的路径改过而缓存没跟上，把名单重置成空就会因为
	 * "空 == 缓存里的空"被当成没变化直接 return——
	 * 界面上的列表清空了，内部名单却还在，正是"重置按钮没用"的原因。
	 */
	public static void setRecordSources(List<String> sources) {
		List<String> wanted = sources == null ? List.of() : List.copyOf(sources);

		synchronized (LOCK) {
			if (wanted.equals(recordSourceInput) && wanted.equals(sourcesOf(RECORDED))) {
				return;
			}

			recordSourceInput = wanted;
			RECORDED.clear();

			for (String source : wanted) {
				BlockMatchRule rule = BlockMatchRule.parse(source);
				if (rule != null && !hasRecord(rule.source())) {
					RECORDED.add(rule);
				}
			}

			commit();
		}
	}

	private static List<String> sourcesOf(List<BlockMatchRule> rules) {
		return rules.stream().map(BlockMatchRule::source).toList();
	}

	public static boolean hasRecord(String source) {
		String trimmed = source.trim();
		for (BlockMatchRule rule : RECORDED) {
			if (rule.source().equalsIgnoreCase(trimmed)) {
				return true;
			}
		}

		return false;
	}

	public static RecordMode getRecordMode() {
		return config.recordMode();
	}

	public static void setRecordMode(RecordMode newMode) {
		synchronized (LOCK) {
			if (newMode == null || newMode == recordMode) {
				return;
			}

			recordMode = newMode;
			commit();
		}

		Log.say("[state] record mode {}", newMode.name());
	}

	public static RecordApplyMode getRecordApplyMode() {
		return config.recordApplyMode();
	}

	public static void setRecordApplyMode(RecordApplyMode newMode) {
		if (newMode == null) {
			return;
		}

		synchronized (LOCK) {
			if (newMode == recordApplyMode) {
				return;
			}

			recordApplyMode = newMode;
			commit();
		}

		Log.say("[state] record apply mode {}", newMode.name());
	}

	public static boolean isInvert() {
		return config.invert();
	}

	/**
	 * 切换"反转"。
	 *
	 * <p>代价同 {@link #setMode}：被淡化的那一批整体翻转，等于全世界的可见性都变了，
	 * 只能全量重建（外加 {@link RebuildScheduler#rebuildChunks()} 里的光照重算）。
	 */
	public static void setInvert(boolean value) {
		synchronized (LOCK) {
			if (value == invert) {
				return;
			}

			invert = value;
			commit();
		}

		Log.say("[state] invert {}", value);
		RebuildScheduler.rebuildChunks();
	}

	/**
	 * 配置项"穿透交互"的<b>原始值</b>（就是落盘的那个布尔量）。
	 *
	 * <p>配置界面读写用这个：它必须是"玩家自己勾的什么"，
	 * 不能掺进"模式是不是关着"——否则模式关闭时会回灌一个 false 到复选框上，
	 * 玩家再勾一次反而把偏好值改没了。
	 */
	public static boolean isPassThrough() {
		return config.passThrough();
	}

	/**
	 * 射线这一帧<b>实际</b>要不要穿透（{@code EntityPickMixin} 用这个）。
	 *
	 * <p>比 {@link #isPassThrough} 多两个前置条件：
	 * <ol>
	 *   <li><b>模式是否关闭</b>：关闭时 {@link Alpha#alphaNow} 一律返回 -1，
	 *       套外壳只会白白分配一个对象，结果完全一样，直接短路掉；</li>
	 *   <li><b>是否手持魔杖</b>：见下面。</li>
	 * </ol>
	 *
	 * <h2>为什么手持魔杖时强制关闭</h2>
	 * <p>魔杖的选点也是用 {@code Entity.pick} 打射线的
	 * （{@code MouseHandlerMixin} 里 {@code camera.pick(MAX_TRACE_DISTANCE, ...)}，
	 * 距离 200 格，专门用来点远处的角点）。
	 * 穿透一旦生效，魔杖就会选到淡化方块<b>后面</b>的那个方块，
	 * 而玩家看到、想选的正是那个淡化方块本身——手感完全反了。
	 *
	 * <p>所以手持魔杖时一律退回"淡化方块照样挡住射线"，
	 * 与魔杖上线前的行为一致。
	 */
	public static boolean isPassThroughActive() {
		RenderConfig current = config;
		return current.passThrough() && current.mode() != Mode.OFF && !SelectiveRendering.isWandHeld();
	}

	/**
	 * 切换"穿透交互"。
	 *
	 * <p>与 {@link #setMode} / {@link #setInvert} 不同，<b>不需要重建区块</b>：
	 * 本开关只影响"射线怎么算"，不参与画面渲染，下一帧的准星就变了。
	 * 但仍然要 {@code commit()}——它得落盘。
	 */
	public static void setPassThrough(boolean value) {
		synchronized (LOCK) {
			if (value == passThrough) {
				return;
			}

			passThrough = value;
			commit();
		}

		Log.say("[state] pass through {}", value);
	}

	/**
	 * 记录器用的判定：这个方块是不是"我们关心的那一类"。
	 * 名单是 {@link #RECORDED}（配置里的 {@code recorded}），过滤方向由 {@code recordMode} 决定。
	 * 与渲染用的 {@link #RULES} 完全独立。
	 *
	 * <p><b>名单为空时一律返回 true</b>（不过滤）。理由：默认现在是白名单，
	 * 而"白名单 + 空名单"按字面语义是什么都录不到，玩家会以为记录器坏了。
	 * 与其静默失效，不如退化成"记录所有变化"，并在 {@code BlockChangeRecorder.start()} 里提示一句。
	 */
	public static boolean matchesRecorded(BlockState state, boolean moving) {
		RenderConfig current = config;
		List<BlockMatchRule> rules = current.recorded();
		if (rules.isEmpty()) {
			return true;
		}

		boolean listed = false;

		for (BlockMatchRule rule : rules) {
			if (rule.matches(state, moving)) {
				listed = true;
				break;
			}
		}

		return current.recordMode() == RecordMode.LISTED ? listed : !listed;
	}

	public static String getWand() {
		return config.wand();
	}

	public static void setWand(String value) {
		String trimmed = value == null ? "" : value.trim();
		if (trimmed.isEmpty()) {
			trimmed = DEFAULT_WAND;
		}

		synchronized (LOCK) {
			if (trimmed.equals(wand)) {
				return;
			}

			wand = trimmed;
			commit();
		}
	}

	public static boolean isFullBright() {
		return config.fullBright();
	}

	/**
	 * 夜视是否真的生效。
	 *
	 * <p>模式为 {@link Mode#OFF} 时必须返回 false——那时整个 Mod 不应该对原版
	 * 有任何影响，夜视（改光照贴图）也不例外。
	 */
	public static boolean isNightVisionActive() {
		RenderConfig current = config;
		return current.mode() != Mode.OFF && current.fullBright();
	}

	/**
	 * 切换"夜视"。
	 *
	 * <p>切换前会先在当前状态下做一次全量重建 + 光照重算，把之前可能污染过的
	 * 光照数据还原干净，然后再切标志并按新行为重建一次。
	 * 顺序不能反——{@link LightSuppression#relight()} 是否工作取决于当前 {@code fullBright} 的值。
	 */
	public static void setFullBright(boolean value) {
		if (value == isFullBright()) {
			return;
		}

		RebuildScheduler.rebuildChunks();

		synchronized (LOCK) {
			fullBright = value;
			commit();
		}

		RebuildScheduler.rebuildChunks();
	}

	private static BlockPos toBlockPos(int[] values) {
		return values == null || values.length != 3 ? null : new BlockPos(values[0], values[1], values[2]);
	}

	private static int[] toArray(BlockPos pos) {
		return pos == null ? null : new int[] {pos.getX(), pos.getY(), pos.getZ()};
	}

	private static int[] toArray(Region region) {
		return new int[] {
			region.min().getX(), region.min().getY(), region.min().getZ(),
			region.max().getX(), region.max().getY(), region.max().getZ()
		};
	}

	private static Region toRegion(int[] values) {
		return values == null || values.length != 6 ? null : new Region(
			new BlockPos(values[0], values[1], values[2]),
			new BlockPos(values[3], values[4], values[5])
		);
	}

	public static List<Preset> presets() {
		return config.presets();
	}

	/**
	 * 应用一个预设：整体替换模式 / 透明度 / 名单 / 选区，然后全量重建。
	 *
	 * <p>注意它<b>不走</b> {@link #setMode}，而是直接改 {@code mode} 字段再统一 rebuild，
	 * 避免中间态触发两次全量重建。
	 */
	public static void applyPreset(Preset preset) {
		synchronized (LOCK) {
			mode = preset.mode == null ? DEFAULT_MODE : preset.mode;
			transparency = Math.clamp(preset.transparency, 0, MAX_TRANSPARENCY);
			invert = preset.invert;

			RULES.clear();
			if (preset.rules != null) {
				for (String source : preset.rules) {
					BlockMatchRule rule = BlockMatchRule.parse(source);
					if (rule != null) {
						RULES.add(rule);
					}
				}
			}

			REGIONS.clear();
			if (preset.regions != null) {
				for (String source : preset.regions) {
					Region added = Region.parse(source);
					if (added != null && !REGIONS.contains(added)) {
						REGIONS.add(added);
					}
				}
			}

			commitAndRemember();
		}

		Log.say("[state] preset applied: {}", preset.displayName());
		RebuildScheduler.rebuildChunks();
	}

	/** 预设对象被原地改名后调用：只落盘，不需要动快照。 */
	public static void savePresets() {
		synchronized (LOCK) {
			persist();
		}
	}

	public static void setPresets(List<Preset> presets) {
		synchronized (LOCK) {
			PRESETS.clear();

			if (presets != null) {
				for (Preset preset : presets) {
					if (preset != null) {
						PRESETS.add(preset);
					}
				}
			}

			commit();
		}
	}

	/**
	 * 必须在 {@link #LOCK} 内调用：刷新不可变快照并请求一次落盘。
	 *
	 * <p>用于"输入文本已经记过"的路径（{@code setRuleSources} 等），
	 * 不需要再写 {@link #rememberSources()}。
	 */
	public static void commit() {
		publish();
		persist();
	}

	/**
	 * 必须在 {@link #LOCK} 内调用：刷新快照 + 记住用户输入的原始文本（防回环）+ 落盘。
	 *
	 * <p>用于"由魔杖 / 记录器改动了内部结构"的路径，此时界面上的文本也要跟着更新。
	 */
	public static void commitAndRemember() {
		publish();
		rememberSources();
		persist();
	}

	/**
	 * 用当前可变集合刷新不可变快照。<b>必须在持有 {@link #LOCK} 时调用。</b>
	 *
	 * <p>这里会生成一个<b>全新的</b> {@link RenderConfig} 实例并整体替换 {@link #config}。
	 * 换实例本身就是缓存失效信号：{@link Alpha} 靠 {@code owner != config} 判断，
	 * 所以任何一条 {@code commit*} 路径都会自动让所有线程的缓存失效，不需要额外清理。
	 */
	private static void publish() {
		config = RenderConfig.of(
			mode,
			transparency,
			invert,
			List.copyOf(RULES),
			List.copyOf(RECORDED),
			List.copyOf(REGIONS),
			List.copyOf(PRESETS),
			recordMode,
			recordApplyMode,
			wand,
			fullBright,
			passThrough,
			corner1,
			corner2,
			region
		);
	}

	/**
	 * 记住"用户输入的原始文本"。
	 *
	 * <p>作用：配置界面（MaLiLib 字符串列表）每次同步都会把文本回灌给
	 * {@link #setRuleSources} / {@link #setRegionSources}，
	 * 这两个方法靠跟这里记下的文本比对来判断"是不是我自己刚写进去的"，
	 * 从而避免"写回 → 触发回调 → 再写回"的死循环。
	 */
	private static void rememberSources() {
		ruleSourceInput = RULES.stream().map(BlockMatchRule::source).toList();
		regionSourceInput = REGIONS.stream().map(Region::toSource).toList();

		// 记录名单同样要记：少了这一行，"重置成空"会被 setRecordSources 当成没变化。
		recordSourceInput = RECORDED.stream().map(BlockMatchRule::source).toList();
	}

	/**
	 * 把运行时状态写回 {@link ModConfig} 并立刻落盘。
	 *
	 * <p>已知问题：调用点几乎都在 {@code synchronized} 块外面，
	 * 而这里读了 {@code RULES}/{@code REGIONS}/{@code PRESETS}/{@code corner1/2}，
	 * 属于无锁读可变集合；且每次调用都是一次完整的文件重写（非原子写）。
	 */
	private static void persist() {
		ModConfig config = ModConfig.get();
		config.mode = mode;
		config.transparency = transparency;
		config.recordMode = recordMode;
		config.recordApplyMode = recordApplyMode;
		config.invert = invert;
		config.wand = wand;
		config.fullBright = fullBright;
		config.rules = RULES.stream().map(BlockMatchRule::source).toList();
		config.recorded = RECORDED.stream().map(BlockMatchRule::source).toList();
		config.regionPos1 = toArray(corner1);
		config.regionPos2 = toArray(corner2);
		config.regions = REGIONS.stream().map(SelectiveRenderingManager::toArray).toList();
		config.presets = new ArrayList<>(PRESETS);
		ModConfig.save();
	}
}
